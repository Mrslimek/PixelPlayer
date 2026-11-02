package com.theveloper.pixelplay.data.p2p

import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.presentation.viewmodel.PlaybackStateHolder
import com.theveloper.pixelplay.presentation.viewmodel.StablePlayerState
import androidx.media3.common.Player
import com.theveloper.pixelplay.shared.p2p.P2pCommandAction
import com.theveloper.pixelplay.shared.p2p.P2pPlaybackStatus
import com.theveloper.pixelplay.shared.p2p.P2pRepeatMode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Engine-level loopback over REAL localhost WebSocket pairs. Round 3 adds the shared-session
 * extension (contract section 8): takeover cycle with fresh playbackId, session_conflict
 * detection past the 2 s handoff grace, no false positive within the grace, and
 * absent-field tolerance (a peer without trackHash is never a session participant).
 */
class PeerProtocolEngineLoopbackTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @AfterEach
    fun tearDown() {
        scopes.forEach { scope -> scope.cancel() }
        scopes.clear()
    }

    private class FakeLibrary(
        private val hashOrNull: String?,
        private val ownSong: () -> Song,
        private val hashBySongId: Map<String, String> = emptyMap(),
        private val songByHash: Map<String, Song> = emptyMap(),
    ) : P2pSessionLibrary {
        val resolvableHash = hashOrNull ?: ("deadbeef" + "0".repeat(56))

        override suspend fun trackHashFor(song: Song): String? = hashBySongId[song.id] ?: hashOrNull
        override suspend fun songForHash(trackHash: String): Song? =
            songByHash[trackHash] ?: if (trackHash == resolvableHash) ownSong() else null
        override fun startBackgroundIndexing() = Unit
    }

    private class RecordingPolicy : P2pSessionPolicy {
        data class PeerActive(
            val peerId: String,
            val peerName: String,
            val trackHash: String,
            val trackTitle: String,
            val positionSec: Double,
            val playbackId: String,
        )

        data class Takeover(val song: Song, val positionSec: Double)

        val peerActiveEvents = ConcurrentLinkedQueue<PeerActive>()
        val takeovers = ConcurrentLinkedQueue<Takeover>()

        /** Simulates the local player: a takeover starts the song at the given position. */
        var onTakeover: ((Song, Double) -> Unit)? = null

        /** Simulates pause-on-peer-active: self pauses when it observes a peer active. */
        var onPeerActiveObserved: (() -> Unit)? = null

        override fun onPeerBecameActive(
            peerId: String,
            peerName: String,
            trackHash: String,
            trackTitle: String,
            positionSec: Double,
            playbackId: String,
        ) {
            peerActiveEvents += PeerActive(peerId, peerName, trackHash, trackTitle, positionSec, playbackId)
            onPeerActiveObserved?.invoke()
        }

        override fun onTakeoverRequested(song: Song, positionSec: Double): Boolean {
            takeovers += Takeover(song, positionSec)
            onTakeover?.invoke(song, positionSec)
            return true
        }
    }

    private class Fixture(
        val engine: PeerProtocolEngine,
        val eventLog: ProtocolEventLog,
        val stateHolder: PeerStateHolder,
        val sessionStateHolder: SessionStateHolder,
        val stableState: MutableStateFlow<StablePlayerState>,
        val position: MutableStateFlow<Long>,
        val discoveryListeners: MutableList<NsdPeerDiscovery.Listener>,
        val commandsReceived: ConcurrentLinkedQueue<Pair<P2pCommandAction, Double?>>,
        val policy: RecordingPolicy,
        val presence: MutableStateFlow<Boolean>,
    )

    private fun newFixture(
        deviceId: String,
        initialPlaying: Boolean,
        initialPositionMs: Long = 0L,
        trackHash: String? = "deadbeef" + "0".repeat(56),
        remoteAllowed: Boolean = true,
        initialSong: Song = testSong(),
        library: P2pSessionLibrary? = null,
    ): Fixture {
        val eventLog = ProtocolEventLog()
        val stateHolder = PeerStateHolder()
        val sessionStateHolder = SessionStateHolder()
        val stableState = MutableStateFlow(
            StablePlayerState(
                currentSong = initialSong,
                isPlaying = initialPlaying,
                playWhenReady = initialPlaying,
                totalDuration = initialSong.duration,
            ),
        )
        val position = MutableStateFlow(initialPositionMs)
        val presence = MutableStateFlow(true) // engine starts when its init collector lands
        val discoveryListeners = mutableListOf<NsdPeerDiscovery.Listener>()
        val commandsReceived = ConcurrentLinkedQueue<Pair<P2pCommandAction, Double?>>()

        val playbackStateHolder = mockk<PlaybackStateHolder>(relaxed = true)
        every { playbackStateHolder.stablePlayerState } returns stableState
        every { playbackStateHolder.currentPosition } returns position

        val prefs = mockk<UserPreferencesRepository>()
        coEvery { prefs.ensureP2pDeviceId() } returns deviceId
        every { prefs.p2pPresenceEnabledFlow } returns presence
        every { prefs.p2pRemoteControlAllowedFlow } returns MutableStateFlow(remoteAllowed)

        val commandExecutor = P2pCommandExecutor { action, positionSec ->
            commandsReceived += action to positionSec
            when (action) {
                P2pCommandAction.PAUSE -> stableState.value =
                    stableState.value.copy(isPlaying = false, playWhenReady = false)
                P2pCommandAction.PLAY -> stableState.value =
                    stableState.value.copy(isPlaying = true, playWhenReady = true)
                // Playback-mode commands (added 2026-11-02): flip the local queue modes the
                // way MediaControllerCommandExecutor does; the flip is observable via the
                // next state_update broadcast.
                P2pCommandAction.REPEAT_OFF -> stableState.value =
                    stableState.value.copy(repeatMode = Player.REPEAT_MODE_OFF)
                P2pCommandAction.REPEAT_ALL -> stableState.value =
                    stableState.value.copy(repeatMode = Player.REPEAT_MODE_ALL)
                P2pCommandAction.REPEAT_ONE -> stableState.value =
                    stableState.value.copy(repeatMode = Player.REPEAT_MODE_ONE)
                P2pCommandAction.SHUFFLE_ON -> stableState.value =
                    stableState.value.copy(isShuffleEnabled = true)
                P2pCommandAction.SHUFFLE_OFF -> stableState.value =
                    stableState.value.copy(isShuffleEnabled = false)
                else -> Unit
            }
            true
        }

        val discoveryFactory = PeerDiscoveryFactory { _, listener ->
            synchronized(discoveryListeners) { discoveryListeners += listener }
            object : PeerDiscoveryHandle {
                override fun register(localName: String, port: Int) = Unit
                override fun startBrowsing() = Unit
                override fun stop() = Unit
            }
        }

        val policy = RecordingPolicy()
        val sessionLibrary = library ?: FakeLibrary(trackHash, ownSong = { testSong() })

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopes += scope
        val engine = PeerProtocolEngine(
            context = mockk(relaxed = true),
            eventLog = eventLog,
            peerStateHolder = stateHolder,
            playbackStateHolder = playbackStateHolder,
            userPreferencesRepository = prefs,
            wsClient = PeerWsClient(OkHttpClient()),
            commandExecutor = commandExecutor,
            discoveryFactory = discoveryFactory,
            sessionLibrary = sessionLibrary,
            sessionPolicy = policy,
            sessionStateHolder = sessionStateHolder,
            appScope = scope,
        )
        return Fixture(
            engine, eventLog, stateHolder, sessionStateHolder, stableState, position,
            discoveryListeners, commandsReceived, policy, presence,
        )
    }

    /** Polls [condition] up to [timeoutMs] (real time; the engines run on real dispatchers). */
    private fun await(timeoutMs: Long = 20_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private fun connectPeers(a: Fixture, aId: String, b: Fixture, bId: String): Boolean {
        assertTrue(await { b.engine.identity.value.port > 0 }, "engine B server never started")
        // Contract section 3 tie-break: a's id < b's id -> A dials B.
        a.discoveryListeners.last().onPeerFound(
            peerId = bId,
            name = "EngineB",
            host = "127.0.0.1",
            port = b.engine.identity.value.port,
            elapsedDiscoveryMs = 5,
        )
        return await { a.stateHolder.peers.value[bId]?.connectionState == P2pConnState.CONNECTED }
    }

    @Test
    fun `command flows end to end - A sends pause, B applies and broadcasts the flip`() {
        val aId = "aaaaaaaa-0000-0000-0000-00000000000a"
        val bId = "bbbbbbbb-0000-0000-0000-00000000000b"
        val a = newFixture(aId, initialPlaying = false)
        val b = newFixture(bId, initialPlaying = true)

        try {
            assertTrue(connectPeers(a, aId, b, bId), "engines never connected")

            a.engine.sendCommand(bId, P2pCommandAction.PAUSE)

            assertTrue(
                await { b.commandsReceived.contains(P2pCommandAction.PAUSE to null) },
                "B never received the pause command",
            )
            // The effect is observable through B's subsequent state_update broadcast (section 6).
            assertTrue(
                await {
                    a.stateHolder.peers.value[bId]?.playback?.status == P2pPlaybackStatus.PAUSED
                },
                "A never observed B's paused state_update",
            )
            assertEquals(
                true,
                b.eventLog.events.value.any {
                    it.direction == ProtocolEventLog.Direction.IN && it.text.startsWith("command from ") &&
                        it.text.endsWith(": pause")
                },
                "B event log missing the applied-command entry: " +
                    b.eventLog.events.value.joinToString(" | ") { it.rendered },
            )
            assertEquals(
                true,
                a.eventLog.events.value.any {
                    it.direction == ProtocolEventLog.Direction.OUT &&
                        it.text.startsWith("command to ") && it.text.endsWith(": pause")
                },
                "A event log missing the sent-command entry: " +
                    a.eventLog.events.value.joinToString(" | ") { it.rendered },
            )
        } finally {
            a.presence.value = false
            b.presence.value = false
        }
    }

    @Test
    fun `gate disabled - inbound command is ignored and never applied`() {
        val aId = "aaaaaaaa-0000-0000-0000-00000000001a"
        val bId = "bbbbbbbb-0000-0000-0000-00000000001b"
        val a = newFixture(aId, initialPlaying = false)
        val b = newFixture(bId, initialPlaying = true, remoteAllowed = false)

        try {
            assertTrue(connectPeers(a, aId, b, bId))

            a.engine.sendCommand(bId, P2pCommandAction.PAUSE)

            assertTrue(
                await {
                    b.eventLog.events.value.any {
                        it.direction == ProtocolEventLog.Direction.IN &&
                            it.text.startsWith("command from ") &&
                            it.text.endsWith(": pause (ignored: disabled)")
                    }
                },
                "B never logged command_ignored (disabled): " +
                    b.eventLog.events.value.joinToString(" | ") { it.rendered },
            )
            assertTrue(b.commandsReceived.isEmpty(), "B's executor must not run when the gate is off")
            assertFalse(
                a.stateHolder.peers.value[bId]?.playback?.status == P2pPlaybackStatus.PAUSED,
                "B must stay playing when remote control is disabled",
            )
        } finally {
            a.presence.value = false
            b.presence.value = false
        }
    }

    @Test
    fun `unsupported action - ignored with reason unsupported and no disconnect`() {
        val aId = "aaaaaaaa-0000-0000-0000-00000000002a"
        val bId = "bbbbbbbb-0000-0000-0000-00000000002b"
        val a = newFixture(aId, initialPlaying = false)
        val b = newFixture(bId, initialPlaying = true)

        try {
            assertTrue(connectPeers(a, aId, b, bId))

            // Unknown action token on the wire -> command_ignored (unsupported), connection stays.
            val frame = """{"v":1,"type":"command","id":"u1","from":"$aId","ts":123,"action":"rewind"}"""
            // The receiver-side record for the sender is keyed by the sender's id.
            b.engine.onRawFrameForTest(aId, frame)

            assertTrue(
                await {
                    b.eventLog.events.value.any {
                        it.direction == ProtocolEventLog.Direction.IN &&
                            it.text.startsWith("command from ") &&
                            it.text.endsWith(": rewind (ignored: unsupported)")
                    }
                },
                "B never logged command_ignored (unsupported): " +
                    b.eventLog.events.value.joinToString(" | ") { it.rendered },
            )
            assertTrue(b.commandsReceived.isEmpty())

            assertTrue(
                await { a.stateHolder.peers.value[bId]?.connectionState == P2pConnState.CONNECTED },
                "unsupported command must never disconnect",
            )
        } finally {
            a.presence.value = false
            b.presence.value = false
        }
    }

    @Test
    fun `takeover cycle - B resumes A's stint with a fresh playbackId at A's position`() {
        val aId = "aaaaaaaa-0000-0000-0000-00000000003a"
        val bId = "bbbbbbbb-0000-0000-0000-00000000003b"
        // A holds the active stint: playing "Fake Track 01" at 42.5s. B is idle with the same
        // synced library (identical hash) and its own distinct local Song instance.
        val a = newFixture(aId, initialPlaying = true, initialPositionMs = 42_500L)
        val b = newFixture(bId, initialPlaying = false)

        // Simulate B's player on takeover: play the resolved song at that position.
        b.policy.onTakeover = { song, positionSec ->
            b.stableState.value = b.stableState.value.copy(
                currentSong = song,
                isPlaying = true,
                playWhenReady = true,
            )
            b.position.value = (positionSec * 1000).toLong()
        }

        try {
            assertTrue(connectPeers(a, aId, b, bId))

            // B (policy) sees A become active: trackHash + A's position + A's playbackId.
            assertTrue(
                await { b.policy.peerActiveEvents.isNotEmpty() },
                "B's policy never saw A become active",
            )
            val aStint = b.policy.peerActiveEvents.first()
            assertEquals(aId, aStint.peerId)
            assertEquals("deadbeef" + "0".repeat(56), aStint.trackHash)
            assertEquals(42.5, aStint.positionSec, 0.05)
            val aPlaybackId = aStint.playbackId

            // Takeover: B resumes A's stint here from its own copy.
            val takeoverResult = kotlinx.coroutines.runBlocking { b.engine.playHere(aId) }
            assertTrue(takeoverResult, "takeover failed")

            // The policy got the locally resolved file at A's last-known position...
            assertEquals(1, b.policy.takeovers.size, "exactly one takeover request expected")
            val takeover = b.policy.takeovers.first()
            assertEquals("Fake Track 01", takeover.song.title)
            assertEquals(42.5, takeover.positionSec, 0.05)

            // ...A observes B active with a FRESH playbackId, broadcasting A's position.
            assertTrue(
                await {
                    val pb = a.stateHolder.peers.value[bId]?.playback
                    pb?.status == P2pPlaybackStatus.PLAYING &&
                        pb.playbackId != null && pb.playbackId != aPlaybackId
                },
                "A never observed B's takeover stint with a fresh playbackId: " +
                    a.eventLog.events.value.takeLast(6).joinToString(" | ") { it.rendered },
            )
            val bStintOnA = a.stateHolder.peers.value[bId]?.playback!!
            assertEquals(42.5, bStintOnA.positionSec, 0.3)

            // A's own policy fired the pause-on-peer-active hook (reference default policy).
            assertTrue(
                a.policy.peerActiveEvents.any { it.peerId == bId },
                "A's policy never saw B become active (pause policy input missing)",
            )
            // Both sides' session state agrees on who holds what. Post-takeover the stint
            // lives on B, so B's session view is LOCAL (its own fresh stint); A never
            // stopped playing (the test policy only records peer-active events), so A's
            // view is its own LOCAL stint — the new holder never silently re-points a
            // still-playing device's view at a peer (conflicts surface via the banner
            // instead, asserted by the conflict test below). Each side's per-peer view of
            // the other's stint is asserted above (aStint on B, bStintOnA on A).
            val bSource = b.sessionStateHolder.state.value.source
            assertTrue(bSource is SessionSource.Local, "B's view must be its own local stint, was $bSource")
            val bLocal = (bSource as SessionSource.Local).stint
            assertEquals("deadbeef" + "0".repeat(56), bLocal.trackHash)
            assertEquals(42.5, bLocal.positionSec, 0.3)
            assertEquals(P2pPlaybackStatus.PLAYING, bLocal.status)
            assertNotEquals(aPlaybackId, bLocal.playbackId, "takeover stint must carry a fresh playbackId")
            assertNull(b.sessionStateHolder.state.value.remotePeer, "B holds the stint itself; no remote peer")
            val aSource = a.sessionStateHolder.state.value.source
            assertTrue(aSource is SessionSource.Local, "A's own still-playing stint keeps the local view, was $aSource")
        } finally {
            a.presence.value = false
            b.presence.value = false
        }
    }

    @Test
    fun `session conflict - two playing past the 2s grace emits a conflict naming both devices`() {
        val aId = "aaaaaaaa-0000-0000-0000-00000000004a"
        val bId = "bbbbbbbb-0000-0000-0000-00000000004b"
        val a = newFixture(aId, initialPlaying = true)
        val b = newFixture(bId, initialPlaying = true)

        try {
            assertTrue(connectPeers(a, aId, b, bId))

            assertTrue(
                await(timeoutMs = 10_000) {
                    a.sessionStateHolder.state.value.conflictActive &&
                        a.sessionStateHolder.state.value.conflicts.isNotEmpty()
                },
                "A never detected the session conflict",
            )
            assertTrue(
                await(timeoutMs = 10_000) {
                    b.sessionStateHolder.state.value.conflictActive &&
                        b.sessionStateHolder.state.value.conflicts.isNotEmpty()
                },
                "B never detected the session conflict",
            )

            val eventA = a.sessionStateHolder.state.value.conflicts.first()
            assertTrue(eventA.deviceIds.contains(aId) && eventA.deviceIds.contains(bId))
            assertEquals(2, eventA.deviceIds.size)
            assertTrue(a.eventLog.events.value.any { it.text.startsWith("session conflict: ") })

            // The violation persists -> still active, and no duplicate event storm.
            Thread.sleep(2_200)
            assertTrue(a.sessionStateHolder.state.value.conflictActive)
            assertTrue(a.sessionStateHolder.state.value.conflicts.size <= 2)
        } finally {
            a.presence.value = false
            b.presence.value = false
        }
    }

    @Test
    fun `session grace - two playing briefly does not emit a conflict`() {
        val aId = "aaaaaaaa-0000-0000-0000-00000000005a"
        val bId = "bbbbbbbb-0000-0000-0000-00000000005b"
        val a = newFixture(aId, initialPlaying = true)
        val b = newFixture(bId, initialPlaying = true)

        try {
            assertTrue(connectPeers(a, aId, b, bId))
            // Legal handoff overlap: keep the double-play window inside the 2 s grace.
            Thread.sleep(1_000)
            assertFalse(
                a.sessionStateHolder.state.value.conflictActive,
                "conflict fired inside the handoff grace (false positive)",
            )
            assertFalse(
                b.sessionStateHolder.state.value.conflictActive,
                "conflict fired inside the handoff grace (false positive)",
            )
            assertTrue(a.eventLog.events.value.none { it.text.startsWith("session conflict: ") })
        } finally {
            a.presence.value = false
            b.presence.value = false
        }
    }

    @Test
    fun `absent trackHash - peer is never a session participant (no conflicts, not capable)`() {
        val aId = "aaaaaaaa-0000-0000-0000-00000000006a"
        val bId = "bbbbbbbb-0000-0000-0000-00000000006b"
        // A does not implement the session extension (hash resolves to null -> fields omitted).
        val a = newFixture(aId, initialPlaying = true, trackHash = null)
        val b = newFixture(bId, initialPlaying = true)

        try {
            assertTrue(connectPeers(a, aId, b, bId))
            // Both are playing, but only B is session-capable -> the invariant holds.
            Thread.sleep(3_500) // past the 2 s grace
            assertFalse(
                a.sessionStateHolder.state.value.conflictActive,
                "non-capable peer counted as a session participant",
            )
            assertFalse(
                b.sessionStateHolder.state.value.conflictActive,
                "non-capable peer counted as a session participant",
            )
            assertTrue(a.eventLog.events.value.none { it.text.startsWith("session conflict: ") })
            assertTrue(b.eventLog.events.value.none { it.text.startsWith("session conflict: ") })
            assertFalse(
                b.sessionStateHolder.state.value.sessionCapablePeerIds.contains(aId),
                "A must not be marked session-capable",
            )
        } finally {
            a.presence.value = false
            b.presence.value = false
        }
    }

    @Test
    fun `session view survives peer shutdown - last-known remote stint stays GONE then CONNECTED on reconnect`() {
        val aId = "aaaaaaaa-0000-0000-0000-00000000007a"
        val bId = "bbbbbbbb-0000-0000-0000-00000000007b"
        // A idles; B holds the shared stint, so A's session view is B's remote stint.
        val a = newFixture(aId, initialPlaying = false)
        val b = newFixture(bId, initialPlaying = true, initialPositionMs = 42_500L)

        try {
            assertTrue(connectPeers(a, aId, b, bId))
            assertTrue(
                await { a.sessionStateHolder.state.value.remotePeer?.peerId == bId },
                "A never adopted B's stint as the session view: " +
                    a.eventLog.events.value.takeLast(6).joinToString(" | ") { it.rendered },
            )
            val connected = a.sessionStateHolder.state.value.remotePeer!!
            assertEquals(SessionPeerReachability.CONNECTED, connected.reachability)
            val lastStint = connected.stint

            // B leaves (graceful goodbye). CORE AXIOM: connection != session — A must keep
            // B's last stint visible as last-known state, never falling back to local playback.
            b.presence.value = false
            assertTrue(
                await { a.sessionStateHolder.state.value.remotePeer?.reachability == SessionPeerReachability.GONE },
                "A never marked the vanished peer's stint GONE: " +
                    a.eventLog.events.value.takeLast(6).joinToString(" | ") { it.rendered },
            )
            val survived = a.sessionStateHolder.state.value.remotePeer!!
            assertEquals(bId, survived.peerId)
            assertEquals(lastStint, survived.stint, "last-known stint must survive the disconnect untouched")
            assertFalse(
                a.sessionStateHolder.state.value.source is SessionSource.Local,
                "disconnect must never fall back to local playback",
            )

            // B returns: re-discovery reconnects and a fresh snapshot marks the view CONNECTED.
            b.presence.value = true
            assertTrue(await { b.engine.identity.value.port > 0 }, "B's engine never restarted")
            a.discoveryListeners.last().onPeerFound(
                peerId = bId,
                name = "EngineB",
                host = "127.0.0.1",
                port = b.engine.identity.value.port,
                elapsedDiscoveryMs = 5,
            )
            assertTrue(
                await { a.sessionStateHolder.state.value.remotePeer?.reachability == SessionPeerReachability.CONNECTED },
                "reconnect never restored the session view to CONNECTED: " +
                    a.eventLog.events.value.takeLast(6).joinToString(" | ") { it.rendered },
            )
            assertEquals(bId, a.sessionStateHolder.state.value.remotePeer?.peerId)
        } finally {
            a.presence.value = false
            b.presence.value = false
        }
    }

    private fun testSong() = Song(
        id = "song-1",
        title = "Fake Track 01",
        artist = "Artist",
        artistId = 1L,
        album = "Album",
        albumId = 1L,
        path = "/tmp/fake.mp3",
        contentUriString = "content://media/1",
        albumArtUriString = null,
        duration = 180_000L,
        mimeType = "audio/mpeg",
        bitrate = 128,
        sampleRate = 44_100,
    )

    @Test
    fun `repeat mode control-observe loop - command flips the target and the stint shows it`() {
        val aId = "aaaaaaaa-0000-0000-0000-00000000002a"
        val bId = "bbbbbbbb-0000-0000-0000-00000000002b"
        val a = newFixture(aId, initialPlaying = false)
        val b = newFixture(bId, initialPlaying = false)

        try {
            assertTrue(connectPeers(a, aId, b, bId), "engines never connected")

            // A session-capable (hash-carrying) state_update makes B represent A's stint.
            assertTrue(
                await { b.sessionStateHolder.state.value.remotePeer?.peerId == aId },
                "B never took A's stint into the session view",
            )
            // A (a mode-capable player) reports its queue modes from the first broadcast.
            assertEquals(P2pRepeatMode.OFF, b.sessionStateHolder.state.value.remotePeer?.stint?.repeat)

            // B's UI sends repeat_all; A applies it to its queue...
            b.engine.sendCommand(aId, P2pCommandAction.REPEAT_ALL)
            assertTrue(
                await { a.commandsReceived.contains(P2pCommandAction.REPEAT_ALL to null) },
                "A never received the repeat_all command",
            )
            assertTrue(
                await { a.stableState.value.repeatMode == Player.REPEAT_MODE_ALL },
                "A never flipped its queue mode",
            )

            // ...and A's NEXT state_update carries repeat:"all" -> B's stint shows it.
            assertTrue(
                await { b.sessionStateHolder.state.value.remotePeer?.stint?.repeat == P2pRepeatMode.ALL },
                "B's session view never observed A's repeat=all",
            )

            // Shuffle loop over the same wire fields.
            a.engine.sendCommand(bId, P2pCommandAction.SHUFFLE_ON)
            assertTrue(await { b.stableState.value.isShuffleEnabled })
            assertTrue(
                await { a.sessionStateHolder.state.value.remotePeer?.stint?.shuffle == true },
                "A's session view never observed B's shuffle=true",
            )
        } finally {
            a.presence.value = false
            b.presence.value = false
        }
    }

    // ------------------------------------------------ bug-fix regression tests

    @Test
    fun `track switch updates B's stint key and resolves track2's display song`() {
        val song1 = testSong().copy(id = "t1", title = "Track One", albumArtUriString = "content://art/one")
        val song2 = testSong().copy(id = "t2", title = "Track Two", albumArtUriString = "content://art/two")
        val hash1 = "1111" + "1".repeat(60)
        val hash2 = "2222" + "2".repeat(60)
        val lib = FakeLibrary(
            hashOrNull = null,
            ownSong = { song1 },
            hashBySongId = mapOf("t1" to hash1, "t2" to hash2),
            songByHash = mapOf(hash1 to song1, hash2 to song2),
        )
        val aId = "aaaaaaaa-0000-0000-0000-00000000003a"
        val bId = "bbbbbbbb-0000-0000-0000-00000000003b"
        val a = newFixture(aId, initialPlaying = true, initialSong = song1, library = lib)
        val b = newFixture(bId, initialPlaying = false, library = lib)

        try {
            assertTrue(connectPeers(a, aId, b, bId), "engines never connected")
            assertTrue(
                await { b.sessionStateHolder.state.value.remotePeer?.peerId == aId },
                "B never took A's stint into the session view",
            )
            val firstStint = b.sessionStateHolder.state.value.remotePeer!!.stint
            assertEquals(hash1, firstStint.trackHash)

            // A switches to track2: fresh playbackId + new hash must arrive as a stint
            // KEY change (playbackId|hash), which is what re-drives display resolution.
            a.stableState.value = a.stableState.value.copy(currentSong = song2)
            assertTrue(
                await {
                    val stint = b.sessionStateHolder.state.value.remotePeer?.stint
                    stint?.trackHash == hash2 && stint.playbackId != firstStint.playbackId
                },
                "B's stint key never followed A's track switch",
            )

            // The display-source swap resolves the NEW hash: title + artwork fields of track2.
            val resolver = com.theveloper.pixelplay.presentation.viewmodel.SessionDisplaySource { lib.songForHash(it) }
            val request = resolver.requestFor(b.sessionStateHolder.state.value.source)
            val displaySong = kotlinx.coroutines.runBlocking { resolver.resolveSong(request, localSong = null) }
            assertEquals("Track Two", displaySong?.title)
            assertEquals("content://art/two", displaySong?.albumArtUriString)
        } finally {
            a.presence.value = false
            b.presence.value = false
        }
    }

    @Test
    fun `takeover while peer is PLAYING - taker goes local, peer auto-pauses, no conflict`() {
        val aId = "aaaaaaaa-0000-0000-0000-00000000004a"
        val bId = "bbbbbbbb-0000-0000-0000-00000000004b"
        val a = newFixture(aId, initialPlaying = true)
        val b = newFixture(bId, initialPlaying = false)

        try {
            assertTrue(connectPeers(a, aId, b, bId), "engines never connected")
            assertTrue(
                await { b.sessionStateHolder.state.value.remotePeer?.peerId == aId },
                "B never took A's stint into the session view",
            )
            assertTrue(a.stableState.value.playWhenReady, "precondition: A is playing")
            val takenPosition = b.sessionStateHolder.state.value.remotePeer!!.stint.positionSec

            // B's policy starts local playback of A's track at A's position.
            b.policy.onTakeover = { song, positionSec ->
                b.stableState.value = b.stableState.value.copy(
                    currentSong = song,
                    isPlaying = true,
                    playWhenReady = true,
                    totalDuration = song.duration,
                )
                b.position.value = (positionSec * 1000).toLong()
            }
            // A's pause-on-peer-active policy pauses A when it observes B's active stint.
            a.policy.onPeerActiveObserved = {
                a.stableState.value = a.stableState.value.copy(isPlaying = false, playWhenReady = false)
            }

            assertTrue(kotlinx.coroutines.runBlocking { b.engine.playHere(aId) }, "takeover must be allowed while the peer is playing")

            // The takeover resolved to B's own Local view (self playing broadcast wins).
            assertTrue(
                await { b.sessionStateHolder.state.value.source is SessionSource.Local },
                "B's view never became Local after the takeover",
            )
            val local = b.sessionStateHolder.state.value.source as SessionSource.Local
            assertEquals("deadbeef" + "0".repeat(56), local.stint.trackHash)
            assertEquals(takenPosition, local.stint.positionSec, 0.5)

            // A auto-paused: the single-active invariant holds, so NO conflict is emitted.
            assertTrue(await { !a.stableState.value.playWhenReady }, "A never auto-paused")
            assertTrue(
                await {
                    val conflict = b.sessionStateHolder.state.value.conflictActive
                    val aConflict = a.sessionStateHolder.state.value.conflictActive
                    !conflict && !aConflict
                },
                "a sanctioned takeover must not surface a session conflict",
            )
        } finally {
            a.presence.value = false
            b.presence.value = false
        }
    }
}
