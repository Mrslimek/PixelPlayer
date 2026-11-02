package com.theveloper.pixelplay.data.p2p

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.di.AppScope
import com.theveloper.pixelplay.presentation.viewmodel.PlaybackStateHolder
import com.theveloper.pixelplay.presentation.viewmodel.StablePlayerState
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.shared.p2p.P2pCommand
import com.theveloper.pixelplay.shared.p2p.P2pCommandAction
import com.theveloper.pixelplay.shared.p2p.P2pGoodbye
import com.theveloper.pixelplay.shared.p2p.P2pHello
import com.theveloper.pixelplay.shared.p2p.P2pHelloAck
import com.theveloper.pixelplay.shared.p2p.P2pPeerPlaybackState
import com.theveloper.pixelplay.shared.p2p.P2pPing
import com.theveloper.pixelplay.shared.p2p.P2pPlaybackStatus
import com.theveloper.pixelplay.shared.p2p.P2pPong
import com.theveloper.pixelplay.shared.p2p.P2pProtocolVersion
import com.theveloper.pixelplay.shared.p2p.P2pStateUpdate
import com.theveloper.pixelplay.shared.p2p.P2pTest
import com.theveloper.pixelplay.shared.p2p.PeerWireCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Orchestrates the Music Share peer protocol v1 (docs/PROTOCOL.md) for PixelPlayer:
 * NsdManager discovery, one WebSocket server, outbound dials toward smaller-id peers,
 * handshakes, keepalive, backoff reconnect, newest-wins replacement, and the 1 Hz
 * debounced playback-state broadcast.
 *
 * Lifecycle: presence runs while the user preference is on. MusicService binds it to the
 * mediaPlayback foreground service (start in onCreate, goodbye app_shutdown in onDestroy);
 * the Settings toggle can start/stop it independently of playback for testing.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Singleton
class PeerProtocolEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventLog: ProtocolEventLog,
    private val peerStateHolder: PeerStateHolder,
    private val playbackStateHolder: PlaybackStateHolder,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val wsClient: PeerWsClient,
    private val commandExecutor: P2pCommandExecutor,
    private val discoveryFactory: PeerDiscoveryFactory,
    private val sessionLibrary: P2pSessionLibrary,
    private val sessionPolicy: P2pSessionPolicy,
    private val sessionStateHolder: SessionStateHolder,
    @AppScope private val appScope: CoroutineScope,
) {

    companion object {
        private const val GOODBYE_APP_SHUTDOWN = "app_shutdown"
        private const val GOODBYE_USER_CLOSE = "user_close"
        private const val APP_VERSION = "1.0"

        /** Contract section 8: legal takeovers overlap; longer than this is a violation. */
        const val SESSION_CONFLICT_GRACE_MS = 2_000L
        private const val TAKEOVER_IN_FLIGHT_TIMEOUT_MS = 5_000L
        private const val MAX_SESSION_CONFLICT_EVENTS = 10
    }

    data class LocalIdentity(val id: String, val name: String, val port: Int, val running: Boolean)

    private enum class Role { CALLER, CALLEE }

    private enum class Cause { SUPERSEDED, SHUTDOWN, KEEPALIVE, HANDSHAKE_TIMEOUT, REMOTE, ERROR }

    /** Per-peer protocol record; ALL mutable fields are guarded by [lock]. */
    private inner class PeerRecord(
        val addrKey: String,
    ) {
        var id: String? = null
        var name: String = ""
        var platform: String = ""
        var host: String = ""
        var port: Int = 0
        var role: Role = Role.CALLEE
        var manual: Boolean = false
        var state: P2pConnState = P2pConnState.IDLE
        var socket: PeerSocket? = null
        var discovered = false
        var openAtMs = 0L
        var dialAtMs = 0L
        var lastSeenAtMs = 0L
        var closeCause: Cause? = null
        var handshakeDeadlineMs = 0L
        var connectGen = 0
        var nextReconnectAtMs = 0L
        val backoff = BackoffLadder()
        val msgIds = MessageIdCache()
        val seqTracker = SeqTracker()
        var pingId: String? = null
        var pingSentAtMs = 0L
        var lastPingAtMs = 0L
        var lastPongAtMs = 0L
        var rttMs: Long? = null
        var pendingDial: PeerWsClient.DialTicket? = null
        var lastPlayback: PeerPlaybackSnapshot? = null
        var sessionCapable = false

        val key: String get() = id ?: addrKey
        val displayName: String get() = name.ifEmpty { id?.take(8) ?: addrKey }
        val isCaller: Boolean get() = role == Role.CALLER || manual
    }

    private val lock = Any()
    private val peers = LinkedHashMap<String, PeerRecord>()
    private val socketToPeer = java.util.IdentityHashMap<PeerSocket, PeerRecord>()
    private val pendingDialByGen = HashMap<Int, PeerRecord>()
    private val watchdog = KeepaliveWatchdog()

    private var server: PeerWsServer? = null
    private var discovery: PeerDiscoveryHandle? = null
    @Volatile private var remoteControlAllowed = true
    private var tickJob: Job? = null
    private var playbackJob: Job? = null
    private val startStopMutex = Mutex()
    private val startRequested = AtomicBoolean(false)

    @Volatile private var running = false
    private var localSeq = 1L
    private var lastBroadcast: P2pPeerPlaybackState? = null

    // Shared session (contract section 8)
    private var localPlaybackSongId: String? = null
    private var localPlaybackId: String? = null
    private var localPlaying = false
    private var localTrackHash: String? = null
    private var conflictSinceMs = 0L
    private var conflictEmittedForEpisode = false
    private var lastPeerActiveKey: String? = null // peerId + playbackId, dedups onPeerBecameActive
    // internal (not private) so session-lifecycle unit tests can drive the real
    // watchdog/revert paths without a socket; logic is unchanged.
    internal var takeoverInFlightSinceMs = 0L
    internal var preTakeoverSource: SessionSource? = null

    private val _identity = MutableStateFlow(LocalIdentity(id = "", name = "", port = 0, running = false))
    val identity: StateFlow<LocalIdentity> = _identity.asStateFlow()

    init {
        appScope.launch {
            val deviceId = userPreferencesRepository.ensureP2pDeviceId()
            if (deviceId != null) {
                synchronized(lock) { _identity.value = _identity.value.copy(id = deviceId) }
            }
        }
        appScope.launch {
            userPreferencesRepository.p2pRemoteControlAllowedFlow.collect { allowed ->
                remoteControlAllowed = allowed
            }
        }
        appScope.launch {
            userPreferencesRepository.p2pPresenceEnabledFlow.collect { enabled ->
                setUserPresenceEnabled(enabled)
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** MusicService.onCreate: presence rides the mediaPlayback foreground service. */
    fun onServiceCreated() = ensureStarted()

    /** MusicService.onDestroy: announce a clean departure to every connected peer. */
    fun onServiceDestroyed() = ensureStopped(goodbyeReason = GOODBYE_APP_SHUTDOWN)

    /** Settings toggle: start/stop presence independent of playback (testing path). */
    fun setUserPresenceEnabled(enabled: Boolean) {
        if (enabled) ensureStarted() else ensureStopped(goodbyeReason = GOODBYE_USER_CLOSE)
    }

    private fun ensureStarted() {
        if (!startRequested.compareAndSet(false, true)) return
        appScope.launch {
            startStopMutex.withLock {
                if (running) return@withLock
                try {
                    startEngine()
                } catch (t: Throwable) {
                    // A silent start failure would strand the toggle ON with no engine and
                    // no trace; record it and allow a retry.
                    Timber.tag("P2P").e(t, "presence start failed")
                    eventLog.log(
                        ProtocolEventLog.Direction.ERROR,
                        "presence start failed: ${t.javaClass.simpleName}: ${t.message}",
                        detail = t.stackTraceToString().take(400),
                    )
                    running = false
                    startRequested.set(false)
                }
            }
        }
    }

    private fun ensureStopped(goodbyeReason: String) {
        if (!startRequested.compareAndSet(true, false)) return
        appScope.launch {
            startStopMutex.withLock {
                if (!running) return@withLock
                stopEngine(goodbyeReason)
            }
        }
    }

    private suspend fun startEngine() {
        val deviceId = _identity.value.id.ifEmpty { userPreferencesRepository.ensureP2pDeviceId().orEmpty() }
        if (deviceId.isEmpty()) {
            eventLog.log(ProtocolEventLog.Direction.ERROR, "presence start failed: no device id")
            startRequested.set(false)
            return
        }
        val name = deviceName()
        val wsServer = PeerWsServer(
            scope = appScope,
            onSocketAccepted = ::onSocketAccepted,
            onSocketClosed = { socket, code, reason -> onSocketDetached(socket, code, reason) },
            onText = ::onSocketText,
        )
        val port = wsServer.start()
        if (port == null) {
            eventLog.log(ProtocolEventLog.Direction.ERROR, "presence start failed: WS server could not bind")
            startRequested.set(false)
            return
        }
        val nsd = discoveryFactory.create(deviceId, discoveryListener).also { discovery = it }
        synchronized(lock) {
            running = true
            localSeq = 1L
            lastBroadcast = null
        }
        _identity.value = LocalIdentity(deviceId, name, port, running = true)
        nsd.register(name, port)
        nsd.startBrowsing()
        tickJob = appScope.launch { tickLoop() }
        playbackJob = appScope.launch { playbackBroadcastLoop() }
        appScope.launch { sessionLibrary.startBackgroundIndexing() }
        eventLog.log(
            ProtocolEventLog.Direction.LOCAL,
            "presence on: name=$name id=${deviceId.take(8)}… ws=:$port ip=${localIpv4Addresses().firstOrNull() ?: "?"}",
        )
        Timber.tag("P2P").i("presence started: port=%d", port)
    }

    private suspend fun stopEngine(goodbyeReason: String) {
        running = false
        _identity.value = _identity.value.copy(running = false, port = 0)
        val connected = synchronized(lock) {
            peers.values.filter { it.state == P2pConnState.CONNECTED }.toList()
        }
        for (record in connected) {
            val socket = synchronized(lock) { record.socket } ?: continue
            sendGoodbye(record, socket, goodbyeReason)
        }
        tickJob?.cancel(); tickJob = null
        playbackJob?.cancel(); playbackJob = null
        synchronized(lock) {
            peers.clear()
            socketToPeer.clear()
            pendingDialByGen.clear()
        }
        peerStateHolder.clear()
        synchronized(lock) {
            localPlaybackSongId = null
            localPlaybackId = null
            localPlaying = false
            localTrackHash = null
            conflictSinceMs = 0L
            conflictEmittedForEpisode = false
            lastPeerActiveKey = null
        }
        sessionStateHolder.clear()
        discovery?.stop()
        discovery = null
        server?.stop()
        server = null
        eventLog.log(ProtocolEventLog.Direction.LOCAL, "presence off: goodbye($goodbyeReason) sent, service unregistered")
        Timber.tag("P2P").i("presence stopped (%s)", goodbyeReason)
    }

    // ------------------------------------------------------------------ discovery wiring

    private val discoveryListener = object : NsdPeerDiscovery.Listener {
        override fun onPeerFound(peerId: String, name: String, host: String, port: Int, elapsedDiscoveryMs: Long) {
            handlePeerFound(peerId, name, host, port)
        }

        override fun onPeerRemoved(peerId: String, reason: String) {
            handlePeerRemoved(peerId, reason)
        }

        override fun onAnnounceResult(ok: Boolean, detail: String) {
            if (!ok) eventLog.log(ProtocolEventLog.Direction.ERROR, "announce failed: $detail")
        }
    }

    private fun handlePeerFound(peerId: String, name: String, host: String, port: Int) {
        val myId = _identity.value.id
        if (peerId == myId || peerId.isEmpty()) return
        val record: PeerRecord
        synchronized(lock) {
            val existing = peers[peerId]
            record = existing ?: PeerRecord(peerId).also { peers[peerId] = it }
            record.id = peerId
            if (name.isNotEmpty()) record.name = name
            record.host = host
            record.port = port
            record.discovered = true
            record.lastSeenAtMs = now()
        }
        val caller = P2pTieBreak.shouldDial(myId, peerId)
        eventLog.log(
            ProtocolEventLog.Direction.LOCAL,
            "peer found: ${record.displayName} @ $host:$port (${if (caller) "I dial" else "I wait"})",
        )
        if (caller) {
            synchronized(lock) {
                if (record.state != P2pConnState.CONNECTING &&
                    record.state != P2pConnState.CONNECTED &&
                    record.state != P2pConnState.CLOSING
                ) {
                    record.role = Role.CALLER
                    dial(record)
                }
            }
        }
        publish(record)
    }

    private fun handlePeerRemoved(peerId: String, reason: String) {
        val record = synchronized(lock) { peers[peerId] } ?: return
        // Healthy-socket guard (reference defect 6): a live peer with fresh pongs is never
        // torn down from a discovery hint alone — keepalive (10 s) and the 60 s expiry are
        // the authoritative liveness signals.
        val healthy = synchronized(lock) {
            record.state == P2pConnState.CONNECTED && record.socket != null &&
                record.lastPongAtMs > 0 && now() - record.lastPongAtMs < P2pProtocolSpec.PONG_TIMEOUT_MS
        }
        if (healthy) {
            synchronized(lock) { record.lastSeenAtMs = now() }
            Timber.tag("P2P").d("dns hint ignored: socket healthy (%s)", peerId)
            return
        }
        markGone(record, "peer gone: ${record.displayName} ($reason)", wireReason = reason.substringBefore(':'))
    }

    // ------------------------------------------------------------------ sockets

    private fun onSocketAccepted(socket: PeerSocket) {
        val record = PeerRecord("inbound:${socket.remoteAddress()}")
        synchronized(lock) {
            record.state = P2pConnState.CONNECTING
            record.socket = socket
            record.lastSeenAtMs = now()
            // Callee handshake deadline: 5 s waiting for `hello` (contract section 3).
            record.handshakeDeadlineMs = now() + P2pProtocolSpec.HANDSHAKE_TIMEOUT_MS
            peers[record.addrKey] = record
            socketToPeer[socket] = record
        }
        eventLog.log(ProtocolEventLog.Direction.LOCAL, "inbound connection accepted: ${socket.remoteAddress()}")
        publish(record)
    }

    private fun onSocketText(socket: PeerSocket, text: String) {
        val record = synchronized(lock) { socketToPeer[socket] } ?: return
        handleText(record, text)
    }

    /** Removes the socket->record mapping and nulls the record's socket if it still owns it. */
    private fun detachSocket(socket: PeerSocket): PeerRecord? = synchronized(lock) {
        val record = socketToPeer.remove(socket)
        if (record != null && record.socket === socket) record.socket = null
        record
    }

    private fun onSocketDetached(socket: PeerSocket, code: Int, reason: String) {
        val record = detachSocket(socket) ?: return
        onSocketClosed(record, code, reason)
    }

    private fun dial(record: PeerRecord) {
        val gen: Int
        val oldSocket: PeerSocket?
        synchronized(lock) {
            if (record.state == P2pConnState.CONNECTING || record.state == P2pConnState.CONNECTED) return
            gen = ++record.connectGen
            oldSocket = record.socket
            record.socket = null
            record.state = P2pConnState.CONNECTING
            record.dialAtMs = now()
            record.closeCause = null
            record.handshakeDeadlineMs = 0L
            pendingDialByGen[gen] = record
        }
        oldSocket?.let { supersedeSocket(record, it, "superseded by new dial") }
        eventLog.log(ProtocolEventLog.Direction.LOCAL, "dial ${record.displayName} @ ${record.host}:${record.port}")
        record.pendingDial = wsClient.dial(record.host, record.port, gen, clientListener)
        publish(record)
    }

    private val clientListener = object : PeerWsClient.Listener {
        override fun onOpen(gen: Int, socket: PeerSocket) {
            val record = synchronized(lock) { pendingDialByGen.remove(gen) } ?: run {
                socket.abort()
                return
            }
            synchronized(lock) {
                if (record.connectGen != gen) {
                    // Stale dial: a newer attempt owns the record; abandon this socket.
                    socket.abort()
                    return
                }
                if (record.socket != null && record.socket !== socket) {
                    supersedeSocket(record, record.socket!!, "superseded by redial")
                }
                record.socket = socket
                record.pendingDial = null
                socketToPeer[socket] = record
                // Caller handshake deadline: 5 s from socket open (contract section 3).
                record.handshakeDeadlineMs = now() + P2pProtocolSpec.HANDSHAKE_TIMEOUT_MS
            }
            sendHello(record, socket)
        }

        override fun onText(gen: Int, socket: PeerSocket, text: String) {
            val record = synchronized(lock) { socketToPeer[socket] } ?: return
            handleText(record, text)
        }

        override fun onClosed(gen: Int, socket: PeerSocket, code: Int, reason: String, remote: Boolean) {
            val record = detachSocket(socket) ?: return
            onSocketClosed(record, code, reason)
        }

        override fun onDialFailed(gen: Int, detail: String) {
            val record = synchronized(lock) { pendingDialByGen.remove(gen) } ?: return
            failDial(record, gen, detail)
        }

        override fun onSocketFailed(gen: Int, socket: PeerSocket, detail: String) {
            val record = detachSocket(socket) ?: return
            onSocketClosed(record, P2pProtocolSpec.CLOSE_NORMAL, detail)
        }
    }

    /** A dial whose upgrade never completed within the timeout (reference defect 5). */
    private fun failDial(record: PeerRecord, gen: Int, detail: String) {
        val owned = synchronized(lock) {
            record.connectGen == gen && record.socket == null && record.state == P2pConnState.CONNECTING
        }
        if (!owned) {
            // A newer attempt or a fresh socket owns the record; abandoning silently is fine,
            // but record the stray timeout for diagnosis instead of dropping it.
            Timber.tag("P2P").d("stale dial timeout ignored (%s, gen=%d): %s", record.displayName, gen, detail)
            return
        }
        synchronized(lock) {
            record.connectGen += 1 // abandon the attempt; late socket events go stale
            record.state = P2pConnState.RECONNECTING
            record.pendingDial = null
        }
        eventLog.log(ProtocolEventLog.Direction.CLOSE, "dial failed: ${record.displayName} — $detail")
        scheduleReconnect(record)
        publish(record)
    }

    private fun supersedeSocket(record: PeerRecord, socket: PeerSocket, reason: String) {
        eventLog.log(
            ProtocolEventLog.Direction.CLOSE,
            "superseded: ${record.displayName} — $reason (code=${P2pProtocolSpec.CLOSE_SUPERSEDED})",
        )
        socket.close(P2pProtocolSpec.CLOSE_SUPERSEDED, "superseded")
    }

    // ------------------------------------------------------------------ message handling

    private fun handleText(record: PeerRecord, text: String) {
        val (message, failure) = PeerWireCodec.decode(text)
        if (message == null) {
            when (failure) {
                PeerWireCodec.DecodeFailure.UNKNOWN_TYPE ->
                    eventLog.log(ProtocolEventLog.Direction.IN, "message_unknown from ${record.displayName}")
                else -> {
                    Timber.tag("P2P").w("malformed frame from %s: %s", record.displayName, text.take(200))
                    eventLog.log(
                        ProtocolEventLog.Direction.ERROR,
                        "message_malformed from ${record.displayName}: not a valid frame",
                        detail = text.take(120),
                    )
                }
            }
            return
        }
        PeerWireCodec.envelopeViolation(message)?.let {
            eventLog.log(ProtocolEventLog.Direction.ERROR, "message_malformed from ${record.displayName}: missing:$it")
            return
        }
        if (message.v != P2pProtocolVersion) {
            eventLog.log(
                ProtocolEventLog.Direction.ERROR,
                "message_malformed from ${record.displayName}: unsupported v=${message.v}",
            )
            return
        }
        if (record.msgIds.isDuplicate(message.id)) {
            Timber.tag("P2P").d("message_duplicate from %s id=%s", record.displayName, message.id)
            return
        }
        record.msgIds.remember(message.id)
        // Any valid non-duplicate message is liveness evidence; refresh the discovery clock so
        // an answering peer is never expired by the 60 s backstop (reference defect 1).
        record.lastSeenAtMs = now()
        when (message) {
            is P2pHello -> onHello(record, message)
            is P2pHelloAck -> onHelloAck(record, message)
            is P2pPing -> onPing(record, message)
            is P2pPong -> onPong(record, message)
            is P2pStateUpdate -> onStateUpdate(record, message)
            is P2pTest -> eventLog.log(ProtocolEventLog.Direction.IN, "test from ${record.displayName}: ${message.text}")
            is P2pCommand -> onCommand(record, message)
            is P2pGoodbye -> onGoodbye(record, message)
        }
    }

    private fun onHello(record: PeerRecord, hello: P2pHello) {
        val socket = synchronized(lock) { record.socket } ?: return
        attachIdentity(record, hello.from, hello.name, hello.platform)
        socket.send(
            PeerWireCodec.encode(
                P2pHelloAck(
                    id = UUID.randomUUID().toString(),
                    from = _identity.value.id,
                    ts = now(),
                    replyTo = hello.id,
                    name = _identity.value.name,
                    platform = P2pProtocolSpec.PLATFORM,
                    appVersion = APP_VERSION,
                ),
            ),
        )
        eventLog.log(ProtocolEventLog.Direction.OUT, "hello_ack to ${record.displayName}")
        completeHandshake(record)
    }

    private fun onHelloAck(record: PeerRecord, ack: P2pHelloAck) {
        if (ack.replyTo == null) {
            eventLog.log(ProtocolEventLog.Direction.ERROR, "message_malformed from ${record.displayName}: hello_ack_no_replyTo")
            return
        }
        attachIdentity(record, ack.from, ack.name, ack.platform)
        eventLog.log(
            ProtocolEventLog.Direction.IN,
            "hello_ack from ${record.displayName} (${ack.platform} ${ack.appVersion})",
        )
        completeHandshake(record)
    }

    private fun onPing(record: PeerRecord, ping: P2pPing) {
        val socket = synchronized(lock) { record.socket } ?: return
        socket.send(
            PeerWireCodec.encode(
                P2pPong(id = UUID.randomUUID().toString(), from = _identity.value.id, ts = now(), replyTo = ping.id),
            ),
        )
    }

    private fun onPong(record: PeerRecord, pong: P2pPong) {
        val pingId = pong.replyTo
        val sentAt = synchronized(lock) {
            if (pingId == null || pingId != record.pingId) return
            val sent = record.pingSentAtMs
            record.pingId = null
            record.lastPongAtMs = now()
            sent
        }
        val rtt = now() - sentAt
        synchronized(lock) { record.rttMs = rtt }
        eventLog.log(ProtocolEventLog.Direction.PAIR, "ping/pong rtt=${rtt}ms with ${record.displayName}")
        publish(record)
    }

    private fun onStateUpdate(record: PeerRecord, state: P2pStateUpdate) {
        val status = P2pPlaybackStatus.fromWire(state.status)
        if (status == null) {
            eventLog.log(
                ProtocolEventLog.Direction.ERROR,
                "message_malformed from ${record.displayName}: state_update status=${state.status}",
            )
            return
        }
        val recvAt = now()
        val lastSeqBefore = record.seqTracker.lastSeq
        val conflict = record.seqTracker.onArrival(state.seq, recvAt)
        if (conflict) {
            eventLog.log(
                ProtocolEventLog.Direction.ERROR,
                "state_conflict from ${record.displayName}: arriving_seq=${state.seq} last_seq=$lastSeqBefore",
                detail = "recv_last=${record.seqTracker.lastRecvAtMs} recv_this=$recvAt",
            )
        }
        val snapshot = PeerPlaybackSnapshot(
            track = state.track,
            artist = state.artist,
            status = status,
            positionSec = state.positionSec,
            seq = state.seq,
            receivedAtMs = recvAt,
            trackHash = state.trackHash,
            playbackId = state.playbackId,
        )
        synchronized(lock) {
            record.lastPlayback = snapshot
            if (state.trackHash != null) record.sessionCapable = true
        }
        publish(record, playbackOverride = snapshot)
        eventLog.log(
            ProtocolEventLog.Direction.IN,
            "state_update from ${record.displayName}: track=${state.track}, status=${state.status}, pos=${formatSec(state.positionSec)}s seq=${state.seq}",
        )
        updateSessionViewFromPeer(record, snapshot)
        maybeNotifyPeerActive(record.displayName, record.id, snapshot)
        evaluateSessionInvariant(now())
    }

    /**
     * Section 8 session-view lifecycle from a peer's state_update. CONNECTION and SESSION
     * are different axes: a playing/paused session-capable state REFRESHES the remote view;
     * a state without a trackHash (stopped/no track) RESOLVES the view for that peer.
     * Nothing here ever falls back to local playback.
     */
    private fun updateSessionViewFromPeer(record: PeerRecord, snapshot: PeerPlaybackSnapshot) {
        val peerId = record.id ?: record.addrKey
        val peerName = record.displayName
        if (snapshot.trackHash == null) {
            // Stint ended on the peer (stopped / no track loaded) -> resolve its view.
            sessionStateHolder.update { state ->
                val source = state.source
                if (source is SessionSource.Remote && source.peerId == peerId) {
                    state.copy(source = null)
                } else {
                    state
                }
            }
            return
        }
        val stint = StintSnapshot(
            trackHash = snapshot.trackHash,
            trackTitle = snapshot.track,
            positionSec = snapshot.positionSec,
            playbackId = snapshot.playbackId ?: return,
            status = snapshot.status,
            updatedAtMs = now(),
        )
        sessionStateHolder.update { state ->
            when (val current = state.source) {
                is SessionSource.TakeoverInFlight -> state // self's playing broadcast resolves it
                is SessionSource.Local ->
                    if (current.stint.status == P2pPlaybackStatus.PLAYING) {
                        // Self is actively playing: the both-playing overlap surfaces via
                        // evaluateSessionInvariant's conflict machinery, never here.
                        state
                    } else if (snapshot.status == P2pPlaybackStatus.PLAYING) {
                        // Self's stint is paused/stopped: the actively playing peer's stint
                        // IS the active session view (newest active stint wins).
                        state.copy(
                            source = SessionSource.Remote(
                                peerId = peerId,
                                peerName = peerName,
                                reachability = SessionPeerReachability.CONNECTED,
                                stint = stint,
                            ),
                        )
                    } else {
                        state // a paused peer never steals a paused local view either
                    }
                is SessionSource.Remote ->
                    if (current.peerId == peerId) {
                        // Same peer: refresh the stint (playing/paused) and mark reachable —
                        // we just heard from it.
                        state.copy(
                            source = current.copy(
                                reachability = SessionPeerReachability.CONNECTED,
                                stint = stint,
                            ),
                        )
                    } else if (snapshot.status == P2pPlaybackStatus.PLAYING) {
                        // A different peer started the active stint; newest active stint wins.
                        state.copy(
                            source = SessionSource.Remote(
                                peerId = peerId,
                                peerName = peerName,
                                reachability = SessionPeerReachability.CONNECTED,
                                stint = stint,
                            ),
                        )
                    } else {
                        state // a paused non-owning peer never steals the view
                    }
                null ->
                    state.copy(
                        source = SessionSource.Remote(
                            peerId = peerId,
                            peerName = peerName,
                            reachability = SessionPeerReachability.CONNECTED,
                            stint = stint,
                        ),
                    )
            }
        }
    }

    /** Section 8 policy hook: fires once per (peer, playbackId) stint; the
     *  pause-on-peer-active default lives in the player-side policy. */
    private fun maybeNotifyPeerActive(peerName: String, peerId: String?, snapshot: PeerPlaybackSnapshot) {
        val hash = snapshot.trackHash ?: return
        if (snapshot.status != P2pPlaybackStatus.PLAYING) return
        val playbackId = snapshot.playbackId ?: return
        val key = "${peerId ?: peerName}#$playbackId"
        synchronized(lock) {
            if (lastPeerActiveKey == key) return
            lastPeerActiveKey = key
        }
        sessionPolicy.onPeerBecameActive(
            peerId = peerId ?: peerName,
            peerName = peerName,
            trackHash = hash,
            trackTitle = snapshot.track,
            positionSec = snapshot.positionSec,
            playbackId = playbackId,
        )
        eventLog.log(
            ProtocolEventLog.Direction.IN,
            "peer active: $peerName — ${snapshot.track} @${formatSec(snapshot.positionSec)}s",
        )
    }

    // ------------------------------------------------------------------ remote commands (section 6 command)

    /** Inbound `command`: gate -> validate -> dispatch. Fire-and-forget; the effect is
     *  observable only through this device's subsequent state_update broadcast. Commands
     *  are never relayed or echoed. */
    private fun onCommand(record: PeerRecord, command: P2pCommand) {
        val action = P2pCommandAction.fromWire(command.action)
        if (action == null) {
            // Unsupported action token: command_ignored per contract, never a disconnect.
            eventLog.log(
                ProtocolEventLog.Direction.IN,
                "command from ${record.displayName}: ${command.action} (ignored: unsupported)",
            )
            return
        }
        if (action.needsPosition && command.positionSec == null) {
            // seek without the required positionSec is an invalid frame (section 6).
            eventLog.log(
                ProtocolEventLog.Direction.ERROR,
                "message_malformed from ${record.displayName}: command action=seek missing positionSec",
            )
            return
        }
        if (!remoteControlAllowed) {
            eventLog.log(
                ProtocolEventLog.Direction.IN,
                "command from ${record.displayName}: ${action.wireValue} (ignored: disabled)",
            )
            return
        }
        val applied = commandExecutor.execute(action, command.positionSec)
        if (!applied) {
            eventLog.log(
                ProtocolEventLog.Direction.IN,
                "command from ${record.displayName}: ${action.wireValue} (ignored: unsupported)",
            )
            return
        }
        eventLog.log(
            ProtocolEventLog.Direction.IN,
            "command from ${record.displayName}: ${describeCommand(action, command.positionSec)}",
        )
    }

    /** Sends a `command` to one connected peer (UI-initiated). Fire-and-forget. */
    fun sendCommand(peerKey: String, action: P2pCommandAction, positionSec: Double? = null) {
        val record = synchronized(lock) { peers[peerKey] } ?: return
        val (socket, name) = synchronized(lock) {
            Pair(record.socket.takeIf { record.state == P2pConnState.CONNECTED }, record.displayName)
        }
        if (socket == null) {
            eventLog.log(
                ProtocolEventLog.Direction.ERROR,
                "command not sent: $name is not connected",
            )
            return
        }
        val sent = socket.send(
            PeerWireCodec.encode(
                P2pCommand(
                    id = UUID.randomUUID().toString(),
                    from = _identity.value.id,
                    ts = now(),
                    action = action.wireValue,
                    positionSec = positionSec,
                ),
            ),
        )
        if (sent) {
            eventLog.log(
                ProtocolEventLog.Direction.OUT,
                "command to $name: ${describeCommand(action, positionSec)}",
            )
        }
    }

    private fun describeCommand(action: P2pCommandAction, positionSec: Double?): String =
        if (action == P2pCommandAction.SEEK && positionSec != null) {
            "seek ${formatSec(positionSec)}s"
        } else {
            action.wireValue
        }

    private fun onGoodbye(record: PeerRecord, goodbye: P2pGoodbye) {
        eventLog.log(ProtocolEventLog.Direction.CLOSE, "goodbye from ${record.displayName} (${goodbye.reason})")
        if (goodbye.reason == GOODBYE_APP_SHUTDOWN || goodbye.reason == GOODBYE_USER_CLOSE) {
            // Contract section 5: an intentional goodbye means the peer is gone. Mark gone
            // BEFORE the close frame arrives so the close path cannot schedule a reconnect
            // (reference defect 3).
            markGone(record, "peer gone: ${record.displayName} (goodbye ${goodbye.reason})", wireReason = goodbye.reason)
        } else {
            synchronized(lock) { record.closeCause = Cause.REMOTE }
        }
    }

    private fun sendHello(record: PeerRecord, socket: PeerSocket) {
        val hello = P2pHello(
            id = UUID.randomUUID().toString(),
            from = _identity.value.id,
            ts = now(),
            name = _identity.value.name,
            platform = P2pProtocolSpec.PLATFORM,
            appVersion = APP_VERSION,
        )
        socket.send(PeerWireCodec.encode(hello))
        eventLog.log(ProtocolEventLog.Direction.OUT, "hello to ${record.displayName}")
    }

    private fun sendGoodbye(record: PeerRecord, socket: PeerSocket, reason: String) {
        val goodbye = P2pGoodbye(id = UUID.randomUUID().toString(), from = _identity.value.id, ts = now(), reason = reason)
        socket.send(PeerWireCodec.encode(goodbye))
        socket.close(P2pProtocolSpec.CLOSE_NORMAL, reason)
        eventLog.log(ProtocolEventLog.Direction.OUT, "goodbye($reason) to ${record.displayName}")
    }

    private fun attachIdentity(record: PeerRecord, id: String, name: String, platform: String) {
        if (id.isEmpty() || id == _identity.value.id) return
        synchronized(lock) {
            record.id = id
            if (name.isNotEmpty()) record.name = name
            if (platform.isNotEmpty()) record.platform = platform
            // Newest-wins (contract section 3): at most ONE connection per peer id. The old
            // entry loses its socket with 4003 and is latched CLOSED so its close path can
            // never schedule a reconnect; its liveness bookkeeping dies with the entry
            // (reference defect 11).
            val other = peers[id]
            if (other != null && other !== record) {
                other.closeCause = Cause.SUPERSEDED
                other.state = P2pConnState.CLOSING
                other.socket?.let { supersedeSocket(other, it, "superseded by newer connection") }
                peers.remove(other.key)
            }
            peers.remove(record.addrKey)
            peers[id] = record
        }
        publish(record)
    }

    private fun completeHandshake(record: PeerRecord) {
        synchronized(lock) {
            if (record.state == P2pConnState.CONNECTED) return
            record.state = P2pConnState.CONNECTED
            record.backoff.reset()
            record.handshakeDeadlineMs = 0L
            record.closeCause = null
            // Defect 1/11: ALL per-connection liveness state resets at handshake completion —
            // a fresh socket must never inherit stale outstanding pings or pong deadlines.
            record.lastPongAtMs = now()
            record.lastPingAtMs = 0L
            record.pingId = null
            record.pingSentAtMs = 0L
        }
        val elapsed = synchronized(lock) { now() - record.dialAtMs }
        eventLog.log(
            ProtocolEventLog.Direction.LOCAL,
            "connected: ${record.displayName} (${if (record.isCaller) "caller" else "callee"}, established ${elapsed}ms)",
        )
        publish(record)
        appScope.launch { sendStateSnapshotTo(record) }
    }

    // ------------------------------------------------------------------ socket close / reconnect

    private fun onSocketClosed(record: PeerRecord, code: Int, reason: String) {
        val cause: Cause = synchronized(lock) {
            // A different live socket already owns the record (supersession) — ignore.
            if (record.socket != null) return
            record.pingId = null
            record.pingSentAtMs = 0L
            record.handshakeDeadlineMs = 0L
            record.closeCause ?: if (record.isCaller) Cause.ERROR else Cause.REMOTE
        }
        markSessionReachability(record.id, SessionPeerReachability.UNREACHABLE)
        when (cause) {
            Cause.SHUTDOWN, Cause.SUPERSEDED -> return
            Cause.KEEPALIVE -> {
                val sincePong = synchronized(lock) { if (record.lastPongAtMs > 0) now() - record.lastPongAtMs else -1L }
                eventLog.log(
                    ProtocolEventLog.Direction.CLOSE,
                    "disconnect from ${record.displayName}: keepalive timeout (last pong ${sincePong}ms ago; remote state unknown)",
                )
                beginReconnectIfEligible(record)
            }
            Cause.HANDSHAKE_TIMEOUT -> {
                eventLog.log(ProtocolEventLog.Direction.CLOSE, "disconnect from ${record.displayName}: handshake timeout (code=$code)")
                beginReconnectIfEligible(record)
            }
            else -> {
                eventLog.log(ProtocolEventLog.Direction.CLOSE, "disconnect from ${record.displayName}: code=$code reason=$reason")
                beginReconnectIfEligible(record)
            }
        }
        publish(record)
    }

    private fun beginReconnectIfEligible(record: PeerRecord) {
        synchronized(lock) {
            val eligible = record.isCaller && (record.discovered || record.manual)
            if (!eligible) {
                if (record.discovered) record.state = P2pConnState.IDLE else peers.remove(record.key)
                return
            }
            record.state = P2pConnState.RECONNECTING
        }
        scheduleReconnect(record)
    }

    private fun scheduleReconnect(record: PeerRecord) {
        val delay = record.backoff.nextDelayMs()
        synchronized(lock) { record.nextReconnectAtMs = now() + delay }
        eventLog.log(
            ProtocolEventLog.Direction.LOCAL,
            "reconnect scheduled: ${record.displayName} in ${delay}ms (attempt ${record.backoff.attempts})",
        )
        publish(record)
    }

    /** Contract section 5: stop when the peer is gone; the ladder resumes only on re-discovery. */
    private fun markGone(record: PeerRecord, eventText: String, wireReason: String) {
        val socket: PeerSocket?
        synchronized(lock) {
            if (record.state == P2pConnState.GONE) return
            record.discovered = false
            record.state = P2pConnState.GONE
            record.closeCause = Cause.SHUTDOWN
            record.handshakeDeadlineMs = 0L
            record.pingId = null
            record.pendingDial?.cancel()
            record.pendingDial = null
            socket = record.socket
            record.socket = null
            socket?.let { socketToPeer.remove(it) }
            peers.remove(record.key)
        }
        eventLog.log(ProtocolEventLog.Direction.LOCAL, eventText)
        socket?.close(P2pProtocolSpec.CLOSE_NORMAL, wireReason)
        peerStateHolder.remove(record.key)
        markSessionReachability(record.id, SessionPeerReachability.GONE)
    }

    /** Connection axis only: the stint data stays exactly as last reported (never
     *  dead-reckoned, never silently replaced by local playback). Internal so the
     *  reachability lifecycle is unit-testable without a socket; logic unchanged. */
    internal fun markSessionReachability(peerId: String?, reachability: SessionPeerReachability) {
        if (peerId == null) return
        sessionStateHolder.update { state ->
            val source = state.source
            if (source is SessionSource.Remote && source.peerId == peerId && source.reachability != reachability) {
                state.copy(source = source.copy(reachability = reachability))
            } else {
                state
            }
        }
    }

    // ------------------------------------------------------------------ keepalive tick

    private suspend fun tickLoop() {
        coroutineScope {
            while (isActive) {
                delay(1_000)
                runCatching { tick(now()) }.onFailure {
                    Timber.tag("P2P").w(it, "tick failed")
                }
            }
        }
    }

    private fun tick(t: Long) {
        watchdogTakeoverInFlight(t)
        evaluateSessionInvariant(t)
        val records = synchronized(lock) { peers.values.toList() }
        for (record in records) {
            val socket = synchronized(lock) { record.socket }
            when (record.state) {
                P2pConnState.CONNECTED -> if (socket != null) tickConnected(record, socket, t)
                P2pConnState.CONNECTING -> {
                    tickHandshakeDeadline(record, socket, t)
                    // Guaranteed dial-timeout escalation (defect 5) even if the transport-level
                    // timeout was lost: a socket-less CONNECTING record past its deadline fails.
                    if (socket == null &&
                        StuckDialDetector.isStuck(record.state, socketPresent = false, dialAtMs = record.dialAtMs, nowMs = t)
                    ) {
                        failDial(record, record.connectGen, "dial stuck past deadline (tick backstop)")
                    }
                }
                P2pConnState.RECONNECTING -> tickReconnect(record, t)
                else -> Unit
            }
            // 60 s expiry backstop (contract section 2): ONLY for a discovered peer with no
            // socket that stopped re-announcing. Never applies to a live connection.
            if (record.discovered &&
                record.state != P2pConnState.CONNECTED &&
                record.socket == null &&
                t - record.lastSeenAtMs > P2pProtocolSpec.PEER_EXPIRY_MS
            ) {
                markGone(record, "peer gone: ${record.displayName} (expired_60s)", wireReason = "expired_60s")
            }
        }
    }

    private fun tickConnected(record: PeerRecord, socket: PeerSocket, t: Long) {
        val snapshot = synchronized(lock) {
            KeepaliveWatchdog.LivenessSnapshot(
                connected = record.state == P2pConnState.CONNECTED,
                socketPresent = record.socket === socket,
                alreadyClosing = record.state == P2pConnState.CLOSING || record.closeCause != null,
                handshakeCompleted = record.handshakeDeadlineMs == 0L,
                lastPongAtMs = record.lastPongAtMs,
            )
        }
        if (watchdog.shouldTeardown(snapshot, t)) {
            beginKeepaliveTeardown(record, socket)
            return
        }
        if (synchronized(lock) { t - record.lastPingAtMs >= P2pProtocolSpec.PING_INTERVAL_MS }) {
            sendPing(record, socket)
        }
    }

    private fun tickHandshakeDeadline(record: PeerRecord, socket: PeerSocket?, t: Long) {
        if (socket == null) return
        val deadline = synchronized(lock) { record.handshakeDeadlineMs }
        if (deadline in 1..t) {
            synchronized(lock) {
                record.closeCause = Cause.HANDSHAKE_TIMEOUT
                record.state = P2pConnState.CLOSING
            }
            eventLog.log(
                ProtocolEventLog.Direction.CLOSE,
                "handshake timeout: ${record.displayName} (role=${if (record.isCaller) "caller" else "callee"})",
            )
            socket.close(P2pProtocolSpec.CLOSE_HANDSHAKE_TIMEOUT, "handshake timeout")
        }
    }

    private fun tickReconnect(record: PeerRecord, t: Long) {
        val due = synchronized(lock) {
            (record.discovered || record.manual) && t >= record.nextReconnectAtMs
        }
        if (due) {
            synchronized(lock) {
                record.role = Role.CALLER
                dial(record)
            }
        }
    }

    /** Section 4 failure verdict, executed exactly once per teardown decision (reference defect 10). */
    private fun beginKeepaliveTeardown(record: PeerRecord, socket: PeerSocket) {
        synchronized(lock) {
            if (record.state != P2pConnState.CONNECTED || record.socket !== socket || record.closeCause != null) return
            record.state = P2pConnState.CLOSING
            record.closeCause = Cause.KEEPALIVE
        }
        eventLog.log(
            ProtocolEventLog.Direction.CLOSE,
            "keepalive timeout: ${record.displayName} — no pong for ${P2pProtocolSpec.PONG_TIMEOUT_MS}ms, closing",
        )
        socket.abort()
    }

    private fun sendPing(record: PeerRecord, socket: PeerSocket) {
        val pingId = UUID.randomUUID().toString()
        synchronized(lock) {
            record.pingId = pingId
            record.pingSentAtMs = now()
            record.lastPingAtMs = now()
        }
        val sent = socket.send(
            PeerWireCodec.encode(P2pPing(id = pingId, from = _identity.value.id, ts = now())),
        )
        if (!sent) {
            // Benign close race: the liveness watchdog owns failure detection.
            Timber.tag("P2P").d("ping skipped: socket closing (%s)", record.displayName)
        }
    }

    // ------------------------------------------------------------------ outbound playback state

    private suspend fun playbackBroadcastLoop() {
        combine(playbackStateHolder.stablePlayerState, playbackStateHolder.currentPosition) { state, positionMs ->
            buildSelfPayload(state, positionMs)
        }
            .sample(P2pProtocolSpec.STATE_BROADCAST_PERIOD_MS)
            .distinctUntilChanged()
            .collect { next ->
                val changed = synchronized(lock) { running && lastBroadcast != next }
                if (!changed) return@collect
                val seq = synchronized(lock) {
                    localSeq += 1
                    lastBroadcast = next
                    localSeq
                }
                broadcastState(next.copy(seq = seq))
            }
    }

    private fun currentSeq(): Long = synchronized(lock) { localSeq }

    /** Builds the outbound state payload with the shared-session fields: the track hash for
     *  the current song (cached-first; hashing on demand runs off-main) and the playbackId
     *  per contract section 8 stint rules (fresh per track start/takeover, kept on pause). */
    private suspend fun buildSelfPayload(state: StablePlayerState, positionMs: Long): P2pPeerPlaybackState {
        val song = state.currentSong
        val status = when {
            song == null -> P2pPlaybackStatus.STOPPED
            // Mirrors MusicService.hasForegroundPlaybackIntent(): a playWhenReady session with
            // media items (and not idle/ended) counts as playing, buffering included.
            state.playWhenReady -> P2pPlaybackStatus.PLAYING
            else -> P2pPlaybackStatus.PAUSED
        }
        val trackHash = song?.let { sessionLibrary.trackHashFor(it) }
        synchronized(lock) {
            if (song == null) {
                localPlaybackSongId = null
                localPlaybackId = null
            } else if (song.id != localPlaybackSongId) {
                // Fresh stint: a different track started (pause->resume keeps the id).
                localPlaybackSongId = song.id
                localPlaybackId = UUID.randomUUID().toString()
            }
        }
        return P2pPeerPlaybackState(
            track = song?.title ?: "",
            artist = song?.artist,
            status = status.wireValue,
            positionSec = positionMs / 1000.0,
            seq = 0L, // stamped per broadcast; handshake snapshots send the current seq as-is
            trackHash = trackHash,
            playbackId = synchronized(lock) { localPlaybackId },
        )
    }

    private fun broadcastState(payload: P2pPeerPlaybackState) {
        publishSelf(payload)
        val records = synchronized(lock) {
            peers.values.filter { it.state == P2pConnState.CONNECTED }.toList()
        }
        if (records.isEmpty()) return
        val json = encodeStateUpdate(payload)
        for (record in records) {
            val socket = synchronized(lock) { record.socket } ?: continue
            if (socket.send(json)) {
                logStateSent(record.displayName, payload)
            }
        }
    }

    /** Post-handshake fresh snapshot: same seq, no increment (contract section 3). */
    private suspend fun sendStateSnapshotTo(record: PeerRecord) {
        val stable = playbackStateHolder.stablePlayerState.value
        val position = playbackStateHolder.currentPosition.value
        val payload = buildSelfPayload(stable, position).copy(seq = currentSeq())
        publishSelf(payload)
        val socket = synchronized(lock) { record.socket } ?: return
        if (socket.send(encodeStateUpdate(payload))) {
            logStateSent(record.displayName, payload)
        }
    }

    private fun encodeStateUpdate(payload: P2pPeerPlaybackState): String = PeerWireCodec.encode(
        P2pStateUpdate(
            id = UUID.randomUUID().toString(),
            from = _identity.value.id,
            ts = now(),
            track = payload.track,
            status = payload.status,
            positionSec = payload.positionSec,
            seq = payload.seq,
            artist = payload.artist,
            trackHash = payload.trackHash,
            playbackId = payload.playbackId,
        ),
    )

    /** Tracks self's session state and re-evaluates the single-active invariant.
     *  LOCAL wins the moment self starts a stint (any local play with a hash) — including
     *  playHere completion, whose playing broadcast clears TAKEOVER_IN_FLIGHT. A paused
     *  local stint keeps the LOCAL view; a stopped stint (no track) resolves it. A local
     *  view is never downgraded to a remote one by connectivity changes.
     *  Internal so the self-broadcast transitions are unit-testable without a
     *  running broadcast loop; logic unchanged. */
    internal fun publishSelf(payload: P2pPeerPlaybackState) {
        synchronized(lock) {
            localPlaying = payload.status == P2pPlaybackStatus.PLAYING.wireValue
            localTrackHash = payload.trackHash
        }
        val selfHash = payload.trackHash
        if (selfHash != null) {
            val stint = StintSnapshot(
                trackHash = selfHash,
                trackTitle = payload.track,
                positionSec = payload.positionSec,
                playbackId = payload.playbackId ?: "",
                status = P2pPlaybackStatus.fromWire(payload.status) ?: P2pPlaybackStatus.STOPPED,
                updatedAtMs = now(),
            )
            sessionStateHolder.update { state ->
                when (val current = state.source) {
                    is SessionSource.Local -> state.copy(source = SessionSource.Local(stint))
                    is SessionSource.TakeoverInFlight, is SessionSource.Remote, null ->
                        if (payload.status == P2pPlaybackStatus.PLAYING.wireValue) {
                            // Self started (or resumed holding) the active stint.
                            state.copy(source = SessionSource.Local(stint))
                        } else {
                            state // paused local data never steals a remote view
                        }
                }
            }
        } else if (payload.status == P2pPlaybackStatus.STOPPED.wireValue) {
            sessionStateHolder.update { state ->
                if (state.source is SessionSource.Local) state.copy(source = null) else state
            }
        }
        evaluateSessionInvariant(now())
    }

    private fun logStateSent(peerName: String, payload: P2pPeerPlaybackState) {
        eventLog.log(
            ProtocolEventLog.Direction.OUT,
            "state_update to $peerName: track=${payload.track}, status=${payload.status}, pos=${formatSec(payload.positionSec)}s",
        )
    }

    // ------------------------------------------------------------------ UI publishing

    private fun publish(record: PeerRecord, playbackOverride: PeerPlaybackSnapshot? = null) {
        val playback = playbackOverride ?: synchronized(lock) { record.lastPlayback }
        peerStateHolder.upsert(peerUiModel(record, playback))
        evaluateSessionInvariant(now())
    }

    private fun peerUiModel(record: PeerRecord, playback: PeerPlaybackSnapshot?): PeerUiModel = synchronized(lock) {
        PeerUiModel(
            key = record.key,
            id = record.id,
            name = record.displayName,
            platform = record.platform,
            connectionState = record.state,
            discovered = record.discovered,
            isCaller = record.isCaller,
            host = record.host,
            port = record.port,
            playback = playback,
            rttMs = record.rttMs,
            reconnectInMs = if (record.state == P2pConnState.RECONNECTING) {
                (record.nextReconnectAtMs - now()).coerceAtLeast(0)
            } else {
                null
            },
            lastSeenAtMs = record.lastSeenAtMs,
            address = if (record.host.isEmpty()) record.addrKey else "${record.host}:${record.port}",
        )
    }

    /** Test-only: injects a raw wire frame as if received from [peerKey]'s socket
     *  (loopback harness exercises receiver rules without a second process). */
    internal fun onRawFrameForTest(peerKey: String, text: String) {
        val record = synchronized(lock) { peers[peerKey] } ?: return
        handleText(record, text)
    }

    /** Test-only: drives the section-8 peer state_update session-view path (the same
     *  segment onStateUpdate runs after publish) without a registered socket peer. */
    internal fun onPeerStateUpdateForTest(peerId: String?, peerName: String, snapshot: PeerPlaybackSnapshot) {
        val record = PeerRecord(addrKey = peerId ?: peerName).apply {
            id = peerId
            name = peerName
            state = P2pConnState.CONNECTED
            lastPlayback = snapshot
            if (snapshot.trackHash != null) sessionCapable = true
        }
        updateSessionViewFromPeer(record, snapshot)
        evaluateSessionInvariant(now())
    }

    // ------------------------------------------------------------------ shared session (section 8)

    /**
     * Single-active invariant (contract section 8): at most one device among {self +
     * connected session-capable peers} playing. Observed, never enforced: >=2 playing for
     * longer than [SESSION_CONFLICT_GRACE_MS] emits one local session_conflict per episode
     * naming the involved devices; it is never auto-resolved.
     */
    private fun evaluateSessionInvariant(t: Long) {
        val playing: List<Pair<String, String>>
        val capable: Set<String>
        synchronized(lock) {
            capable = peers.values.filter { it.sessionCapable }.mapNotNull { it.id }.toSet()
            playing = buildList {
                if (localPlaying && localTrackHash != null) add(_identity.value.id to _identity.value.name)
                for (record in peers.values) {
                    if (record.state != P2pConnState.CONNECTED) continue
                    val pb = record.lastPlayback ?: continue
                    if (pb.status == P2pPlaybackStatus.PLAYING && pb.trackHash != null) {
                        add((record.id ?: record.addrKey) to record.displayName)
                    }
                }
            }
        }
        sessionStateHolder.update { it.copy(sessionCapablePeerIds = capable) }
        if (playing.size >= 2) {
            if (conflictSinceMs == 0L) {
                conflictSinceMs = t
                return
            }
            if (t - conflictSinceMs < SESSION_CONFLICT_GRACE_MS || conflictEmittedForEpisode) return
            conflictEmittedForEpisode = true
            emitSessionConflict(playing)
        } else {
            conflictSinceMs = 0L
            if (conflictEmittedForEpisode) {
                conflictEmittedForEpisode = false
                sessionStateHolder.update { it.copy(conflictActive = false) }
            }
        }
    }

    private fun emitSessionConflict(playing: List<Pair<String, String>>) {
        val ids = playing.map { it.first }
        val names = playing.map { it.second }
        Timber.tag("P2P").w("session conflict: %s both playing", names.joinToString(" + "))
        sessionStateHolder.update { state ->
            state.copy(
                conflictActive = true,
                conflicts = (listOf(SessionConflictEvent(detectedAtMs = now(), deviceIds = ids, deviceNames = names)) +
                    state.conflicts).take(MAX_SESSION_CONFLICT_EVENTS),
            )
        }
        eventLog.log(
            ProtocolEventLog.Direction.WARN,
            "session conflict: ${names.joinToString(" + ")} both playing",
            detail = "device ids: ${ids.joinToString(", ")}",
        )
    }

    /** Takeover (contract section 8): resolve the peer's trackHash locally and resume here.
     *  Hash not in the index -> library_unsynced, surfaced to the user, SILENT on the wire.
     *  Session view: TAKEOVER_IN_FLIGHT from initiation until self's playing broadcast
     *  (LOCAL wins) or a failure restores the previous view; a 5 s watchdog covers a player
     *  that never starts. */
    suspend fun playHere(peerKey: String): Boolean {
        val target = synchronized(lock) {
            val record = peers[peerKey] ?: return false
            val pb = record.lastPlayback ?: return false
            val hash = pb.trackHash ?: return false
            if (pb.status != P2pPlaybackStatus.PLAYING) return false
            TakeoverTarget(record.id ?: record.addrKey, record.displayName, hash, pb.positionSec)
        }
        val previousSource = sessionStateHolder.state.value.source
        synchronized(lock) { preTakeoverSource = previousSource }
        sessionStateHolder.update {
            it.copy(
                source = SessionSource.TakeoverInFlight(
                    peerId = target.peerId,
                    peerName = target.peerName,
                    trackHash = target.trackHash,
                    positionSec = target.positionSec,
                ),
            )
        }
        takeoverInFlightSinceMs = now()
        eventLog.log(
            ProtocolEventLog.Direction.LOCAL,
            "takeover in flight: ${target.peerName}'s ${target.trackHash.take(8)}… @${formatSec(target.positionSec)}s",
        )
        val song = sessionLibrary.songForHash(target.trackHash)
        if (song == null) {
            eventLog.log(
                ProtocolEventLog.Direction.WARN,
                "library not synced: no local copy of ${target.peerName}'s track (hash ${target.trackHash.take(8)}…)",
            )
            sessionStateHolder.update {
                it.copy(
                    source = previousSource,
                    lastUnsynced = LibraryUnsyncedEvent(atMs = now(), peerName = target.peerName, trackHash = target.trackHash),
                )
            }
            takeoverInFlightSinceMs = 0L
            return false // silent takeover attempt: it did not happen
        }
        // Fresh playbackId for the new stint (section 8), stamped before playback starts.
        synchronized(lock) {
            localPlaybackSongId = song.id
            localPlaybackId = UUID.randomUUID().toString()
        }
        val started = sessionPolicy.onTakeoverRequested(song, target.positionSec)
        if (!started) {
            eventLog.log(
                ProtocolEventLog.Direction.ERROR,
                "takeover failed: local player session not available",
            )
            revertTakeover()
            return false
        }
        eventLog.log(
            ProtocolEventLog.Direction.LOCAL,
            "takeover: playing ${song.title} @${formatSec(target.positionSec)}s (was ${target.peerName}'s stint)",
        )
        // Broadcast immediately so the takeover is visible; the status flips to playing as
        // soon as the player reports it and the 1 Hz loop follows up. publishSelf clears
        // TAKEOVER_IN_FLIGHT the moment self broadcasts playing with a hash.
        val stable = playbackStateHolder.stablePlayerState.value
        val payload = buildSelfPayload(stable, playbackStateHolder.currentPosition.value).copy(seq = currentSeq())
        lastBroadcast = null
        broadcastState(payload)
        return true
    }

    private data class TakeoverTarget(
        val peerId: String,
        val peerName: String,
        val trackHash: String,
        val positionSec: Double,
    )

    private fun revertTakeover() {
        takeoverInFlightSinceMs = 0L
        val previous = synchronized(lock) { preTakeoverSource }
        sessionStateHolder.update { it.copy(source = previous) }
    }

    /** Safety net: a takeover whose playing broadcast never arrives cannot wedge the view.
     *  Internal so the timeout path is unit-testable without a socket; logic unchanged. */
    internal fun watchdogTakeoverInFlight(t: Long) {
        if (takeoverInFlightSinceMs == 0L) return
        if (sessionStateHolder.state.value.source !is SessionSource.TakeoverInFlight) {
            takeoverInFlightSinceMs = 0L
            return
        }
        if (t - takeoverInFlightSinceMs >= TAKEOVER_IN_FLIGHT_TIMEOUT_MS) {
            Timber.tag("P2P").w("takeover in flight timed out; restoring previous session view")
            eventLog.log(
                ProtocolEventLog.Direction.WARN,
                "takeover timed out: local playback never started, restoring previous view",
            )
            revertTakeover()
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun formatSec(sec: Double): String = String.format(Locale.US, "%.1f", sec)

    private fun deviceName(): String = runCatching {
        Settings.Secure.getString(context.contentResolver, "bluetooth_name")
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: listOfNotNull(Build.MANUFACTURER, Build.MODEL).joinToString(" ").trim().ifEmpty { "PixelPlay device" }
}
