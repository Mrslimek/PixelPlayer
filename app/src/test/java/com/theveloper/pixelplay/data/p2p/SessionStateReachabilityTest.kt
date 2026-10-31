package com.theveloper.pixelplay.data.p2p

import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.presentation.viewmodel.PlaybackStateHolder
import com.theveloper.pixelplay.presentation.viewmodel.StablePlayerState
import com.theveloper.pixelplay.shared.p2p.P2pPeerPlaybackState
import com.theveloper.pixelplay.shared.p2p.P2pPlaybackStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Shared-session lifecycle (contract section 8) at holder/engine level, no sockets:
 * the CORE AXIOM that connection != session — losing connectivity never silently
 * falls the view back to local playback; the remote stint stays visible as
 * last-known state through CONNECTED -> UNREACHABLE -> GONE and back. Also pins
 * the dead-reckoning ban: the holder stores exactly what was last reported, never
 * extrapolated positions. Transitions are driven through the engine's real entry
 * points (markSessionReachability / publishSelf / watchdogTakeoverInFlight).
 */
class SessionStateReachabilityTest {

    private val scopes = mutableListOf<CoroutineScope>()

    private companion object {
        const val PEER_ID = "bbbbbbbb-0000-0000-0000-00000000004b"
        const val PEER_NAME = "Peer B"
        const val SELF_ID = "aaaaaaaa-0000-0000-0000-00000000004a"
        val PEER_HASH = "deadbeef" + "0".repeat(56)
        val SELF_HASH = "cafebabe" + "0".repeat(56)
    }

    @AfterEach
    fun tearDown() {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    /**
     * A real, never-started engine (presence off, no server, no peers) sharing one
     * real SessionStateHolder — enough to drive the session-view entry points.
     */
    private class Harness(
        val engine: PeerProtocolEngine,
        val sessionStateHolder: SessionStateHolder,
    )

    private fun harness(): Harness {
        val sessionStateHolder = SessionStateHolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopes += scope
        val playbackStateHolder = mockk<PlaybackStateHolder>(relaxed = true)
        every { playbackStateHolder.stablePlayerState } returns MutableStateFlow(
            StablePlayerState(currentSong = null, isPlaying = false, playWhenReady = false),
        )
        every { playbackStateHolder.currentPosition } returns MutableStateFlow(0L)
        val prefs = mockk<UserPreferencesRepository>()
        coEvery { prefs.ensureP2pDeviceId() } returns SELF_ID
        every { prefs.p2pPresenceEnabledFlow } returns MutableStateFlow(false)
        every { prefs.p2pRemoteControlAllowedFlow } returns MutableStateFlow(true)
        val engine = PeerProtocolEngine(
            context = mockk(relaxed = true),
            eventLog = ProtocolEventLog(),
            peerStateHolder = PeerStateHolder(),
            playbackStateHolder = playbackStateHolder,
            userPreferencesRepository = prefs,
            wsClient = PeerWsClient(OkHttpClient()),
            commandExecutor = P2pCommandExecutor { _, _ -> true },
            discoveryFactory = PeerDiscoveryFactory { _, _ ->
                object : PeerDiscoveryHandle {
                    override fun register(localName: String, port: Int) = Unit
                    override fun startBrowsing() = Unit
                    override fun stop() = Unit
                }
            },
            sessionLibrary = mockk(relaxed = true),
            sessionPolicy = mockk(relaxed = true),
            sessionStateHolder = sessionStateHolder,
            appScope = scope,
        )
        return Harness(engine, sessionStateHolder)
    }

    private fun peerStint(positionSec: Double = 42.5, updatedAtMs: Long = 1_700_000_000_000L) =
        StintSnapshot(
            trackHash = PEER_HASH,
            trackTitle = "Peer Track",
            positionSec = positionSec,
            playbackId = "pb-peer-1",
            status = P2pPlaybackStatus.PLAYING,
            updatedAtMs = updatedAtMs,
        )

    private fun remoteSource(stint: StintSnapshot = peerStint()) = SessionSource.Remote(
        peerId = PEER_ID,
        peerName = PEER_NAME,
        reachability = SessionPeerReachability.CONNECTED,
        stint = stint,
    )

    private fun installRemote(h: Harness, stint: StintSnapshot = peerStint()) {
        h.sessionStateHolder.update { it.copy(source = remoteSource(stint)) }
    }

    private fun expectRemoteWithStint(source: SessionSource?, stint: StintSnapshot): SessionSource.Remote {
        assertNotNull(source)
        assertFalse(source is SessionSource.Local, "connectivity change must NEVER fall back to Local")
        assertTrue(source is SessionSource.Remote, "stint must survive as Remote, was $source")
        source as SessionSource.Remote
        assertEquals(stint, source.stint, "stint data must be byte-identical to the last report")
        return source
    }

    @Test
    fun `remote stint survives disconnect as UNREACHABLE with identical last-known data`() {
        val h = harness()
        val stint = peerStint(positionSec = 42.5, updatedAtMs = System.currentTimeMillis() - 60_000)
        installRemote(h, stint)

        h.engine.markSessionReachability(PEER_ID, SessionPeerReachability.UNREACHABLE)

        val remote = expectRemoteWithStint(h.sessionStateHolder.state.value.source, stint)
        assertEquals(SessionPeerReachability.UNREACHABLE, remote.reachability)
        // Dead-reckoning ban: 60 s elapsed since updatedAtMs must not have moved the position.
        assertEquals(42.5, remote.stint.positionSec, 0.0)
    }

    @Test
    fun `remote stint survives GONE with identical data and take-over view stays`() {
        val h = harness()
        val stint = peerStint()
        installRemote(h, stint)

        h.engine.markSessionReachability(PEER_ID, SessionPeerReachability.UNREACHABLE)
        h.engine.markSessionReachability(PEER_ID, SessionPeerReachability.GONE)

        val remote = expectRemoteWithStint(h.sessionStateHolder.state.value.source, stint)
        assertEquals(SessionPeerReachability.GONE, remote.reachability)
        assertEquals(PEER_ID, h.sessionStateHolder.state.value.remotePeer?.peerId)
    }

    @Test
    fun `reconnect restores CONNECTED on the same surviving remote stint`() {
        val h = harness()
        val stint = peerStint()
        installRemote(h, stint)
        h.engine.markSessionReachability(PEER_ID, SessionPeerReachability.GONE)

        h.engine.markSessionReachability(PEER_ID, SessionPeerReachability.CONNECTED)
        val remote = expectRemoteWithStint(h.sessionStateHolder.state.value.source, stint)
        assertEquals(SessionPeerReachability.CONNECTED, remote.reachability)

        // Idempotent: repeating the same reachability must not touch the view at all.
        h.engine.markSessionReachability(PEER_ID, SessionPeerReachability.CONNECTED)
        assertEquals(remote, h.sessionStateHolder.state.value.source)
    }

    @Test
    fun `self playing broadcast wins - Local source replaces the remote view`() {
        val h = harness()
        installRemote(h)

        h.engine.publishSelf(
            P2pPeerPlaybackState(
                track = "Self Track",
                artist = "Me",
                status = P2pPlaybackStatus.PLAYING.wireValue,
                positionSec = 10.0,
                trackHash = SELF_HASH,
                playbackId = "pb-self-1",
            ),
        )

        val source = h.sessionStateHolder.state.value.source
        assertTrue(source is SessionSource.Local, "self playing must switch the view to Local, was $source")
        val stint = (source as SessionSource.Local).stint
        assertEquals(SELF_HASH, stint.trackHash)
        assertEquals("Self Track", stint.trackTitle)
        assertEquals(10.0, stint.positionSec, 0.0)
        assertNull(h.sessionStateHolder.state.value.remotePeer)
    }

    @Test
    fun `self paused broadcast never steals a remote view`() {
        val h = harness()
        val stint = peerStint()
        installRemote(h, stint)

        h.engine.publishSelf(
            P2pPeerPlaybackState(
                track = "Self Track",
                artist = "Me",
                status = P2pPlaybackStatus.PAUSED.wireValue,
                positionSec = 10.0,
                trackHash = SELF_HASH,
                playbackId = "pb-self-1",
            ),
        )

        expectRemoteWithStint(h.sessionStateHolder.state.value.source, stint)
    }

    @Test
    fun `takeover in flight resolves to Local when self playing broadcast arrives`() {
        val h = harness()
        h.sessionStateHolder.update {
            it.copy(
                source = SessionSource.TakeoverInFlight(
                    peerId = PEER_ID,
                    peerName = PEER_NAME,
                    trackHash = PEER_HASH,
                    positionSec = 42.5,
                ),
            )
        }

        h.engine.publishSelf(
            P2pPeerPlaybackState(
                track = "Peer Track",
                artist = "Someone",
                status = P2pPlaybackStatus.PLAYING.wireValue,
                positionSec = 42.5,
                trackHash = PEER_HASH,
                playbackId = "pb-taken-1",
            ),
        )

        val source = h.sessionStateHolder.state.value.source
        assertTrue(source is SessionSource.Local, "takeover completion must land on Local, was $source")
        val stint = (source as SessionSource.Local).stint
        assertEquals(PEER_HASH, stint.trackHash)
        assertEquals(42.5, stint.positionSec, 0.0)
        assertEquals("pb-taken-1", stint.playbackId)
    }

    @Test
    fun `takeover watchdog reverts to the previous remote view after the timeout`() {
        val h = harness()
        val remote = remoteSource()
        installRemote(h, remote.stint)

        // Mirror playHere's bookkeeping, then let the 5 s watchdog window pass.
        h.engine.preTakeoverSource = remote
        h.sessionStateHolder.update {
            it.copy(
                source = SessionSource.TakeoverInFlight(
                    peerId = PEER_ID,
                    peerName = PEER_NAME,
                    trackHash = PEER_HASH,
                    positionSec = 42.5,
                ),
            )
        }
        h.engine.takeoverInFlightSinceMs = System.currentTimeMillis() - 10_000

        // Still in flight -> too early, nothing may revert yet.
        h.engine.watchdogTakeoverInFlight(System.currentTimeMillis() - 9_000)
        assertTrue(h.sessionStateHolder.state.value.source is SessionSource.TakeoverInFlight)

        h.engine.watchdogTakeoverInFlight(System.currentTimeMillis())

        assertEquals(remote, h.sessionStateHolder.state.value.source, "timeout must restore the exact previous view")
        assertEquals(0L, h.engine.takeoverInFlightSinceMs)
    }

    @Test
    fun `holder never dead-reckons - position moves only when a new value is reported`() {
        val h = harness()
        val staleStint = peerStint(positionSec = 42.5, updatedAtMs = System.currentTimeMillis() - 30_000)
        installRemote(h, staleStint)

        // Any number of connection-axis or paused-self updates leave the position EXACTLY
        // at the last reported value no matter how much wall clock passed.
        h.engine.markSessionReachability(PEER_ID, SessionPeerReachability.UNREACHABLE)
        h.engine.publishSelf(
            P2pPeerPlaybackState(
                status = P2pPlaybackStatus.PAUSED.wireValue,
                positionSec = 99.0,
                trackHash = SELF_HASH,
                playbackId = "pb-self-1",
            ),
        )
        h.engine.markSessionReachability(PEER_ID, SessionPeerReachability.CONNECTED)
        assertEquals(staleStint, h.sessionStateHolder.state.value.remotePeer?.stint)
        assertEquals(42.5, staleStint.positionSec, 0.0)

        // Only a fresh report changes the stored position — to exactly its value.
        val fresh = peerStint(positionSec = 11.25, updatedAtMs = System.currentTimeMillis())
        installRemote(h, fresh)
        assertEquals(11.25, h.sessionStateHolder.state.value.remotePeer!!.stint.positionSec, 0.0)
    }
}
