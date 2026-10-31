package com.theveloper.pixelplay.data.p2p

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PeerLogicTest {

    // ---------------------------------------------------------------- tie-break

    @Test
    fun `tie-break - lexicographically smaller device id dials`() {
        assertTrue(P2pTieBreak.shouldDial(myId = "0aaa", peerId = "ffff"))
        assertFalse(P2pTieBreak.shouldDial(myId = "ffff", peerId = "0aaa"))
        assertFalse(P2pTieBreak.shouldDial(myId = "same", peerId = "same"))
        // uuid-shaped ids from the reference MVP session: mac 07f0… dialed toward phone ee2f…
        assertTrue(P2pTieBreak.shouldDial(myId = "07f0…", peerId = "ee2f…"))
        assertFalse(P2pTieBreak.shouldDial(myId = "ee2f…", peerId = "07f0…"))
        assertFalse(P2pTieBreak.shouldDial(myId = "", peerId = "anyone"))
    }

    // ---------------------------------------------------------------- backoff ladder

    @Test
    fun `backoff ladder - exact schedule without jitter`() {
        val ladder = BackoffLadder(jitterFraction = 0.0)
        val delays = (1..10).map { ladder.nextDelayMs() }
        assertEquals(
            listOf(1_500L, 3_000L, 6_000L, 12_000L, 24_000L, 30_000L, 30_000L, 30_000L, 30_000L, 30_000L),
            delays,
        )
    }

    @Test
    fun `backoff ladder - jitter stays within 20 percent`() {
        val ladder = BackoffLadder(jitterFraction = 0.20, random = Random(42))
        repeat(200) {
            val base = ladder.currentBaseMs
            val delay = ladder.nextDelayMs()
            assertTrue(delay >= base * 0.8 - 1 && delay <= base * 1.2 + 1, "delay=$delay base=$base")
        }
    }

    @Test
    fun `backoff ladder - resets only on success`() {
        val ladder = BackoffLadder(jitterFraction = 0.0)
        repeat(3) { ladder.nextDelayMs() }
        assertEquals(12_000L, ladder.currentBaseMs)
        ladder.reset()
        assertEquals(1_500L, ladder.currentBaseMs)
        assertEquals(1_500L, ladder.nextDelayMs())
    }

    // ---------------------------------------------------------------- message id cache

    @Test
    fun `message id cache - duplicate detection over a 128 entry window`() {
        val cache = MessageIdCache()
        val ids = (1..200).map { "id-$it" }
        ids.forEach { cache.remember(it) }
        // The oldest 72 fell out of the window; the newest 128 are still duplicates.
        assertTrue(ids.take(72).all { !cache.isDuplicate(it) })
        assertTrue(ids.drop(72).all { cache.isDuplicate(it) })
    }

    @Test
    fun `message id cache - empty id is never a duplicate`() {
        val cache = MessageIdCache()
        assertFalse(cache.isDuplicate(""))
        cache.remember("")
        assertFalse(cache.isDuplicate(""))
    }

    // ---------------------------------------------------------------- seq tracker

    @Test
    fun `seq tracker - monotonic arrivals are not conflicts`() {
        val tracker = SeqTracker()
        assertFalse(tracker.onArrival(1, 100))
        assertFalse(tracker.onArrival(2, 200))
        assertFalse(tracker.onArrival(3, 300))
        assertEquals(3, tracker.lastSeq)
        assertEquals(300, tracker.lastRecvAtMs)
    }

    @Test
    fun `seq tracker - equal or lower seq is a conflict with receipt times recorded`() {
        val tracker = SeqTracker()
        tracker.onArrival(17, 1_000)
        assertTrue(tracker.onArrival(17, 2_000))
        assertTrue(tracker.onArrival(5, 3_000))
        // After the reset to the newer (lower) value, further regressions still conflict.
        assertTrue(tracker.onArrival(4, 4_000))
        assertFalse(tracker.onArrival(18, 5_000))
    }

    @Test
    fun `seq tracker - first arrival never conflicts even at zero`() {
        val tracker = SeqTracker()
        assertFalse(tracker.onArrival(0, 10))
        assertTrue(tracker.onArrival(0, 20))
    }

    // ---------------------------------------------------------------- keepalive watchdog

    private fun snapshot(
        connected: Boolean = true,
        socketPresent: Boolean = true,
        alreadyClosing: Boolean = false,
        handshakeCompleted: Boolean = true,
        lastPongAtMs: Long,
    ) = KeepaliveWatchdog.LivenessSnapshot(
        connected = connected,
        socketPresent = socketPresent,
        alreadyClosing = alreadyClosing,
        handshakeCompleted = handshakeCompleted,
        lastPongAtMs = lastPongAtMs,
    )

    @Test
    fun `watchdog - keyed on last received pong, not ping-send time`() {
        val watchdog = KeepaliveWatchdog()
        // Defect 2 regression: last pong 15s ago (pings sent every 5s) -> must fire.
        assertTrue(watchdog.shouldTeardown(snapshot(lastPongAtMs = 5_000), nowMs = 20_000))
        // Fresh pong 9.5s ago while pings keep being sent -> must NOT fire.
        assertFalse(watchdog.shouldTeardown(snapshot(lastPongAtMs = 10_500), nowMs = 20_000))
    }

    @Test
    fun `watchdog - fires exactly at the 10s pong budget`() {
        val watchdog = KeepaliveWatchdog()
        assertFalse(watchdog.shouldTeardown(snapshot(lastPongAtMs = 1_000), nowMs = 10_999))
        assertTrue(watchdog.shouldTeardown(snapshot(lastPongAtMs = 1_000), nowMs = 11_000))
    }

    @Test
    fun `watchdog - one-shot latch, never re-fires on a closing connection`() {
        val watchdog = KeepaliveWatchdog()
        // Defect 10 regression: after the teardown latch, later ticks are no-ops.
        assertTrue(watchdog.shouldTeardown(snapshot(lastPongAtMs = 1_000), nowMs = 13_000))
        assertFalse(watchdog.shouldTeardown(snapshot(lastPongAtMs = 1_000, alreadyClosing = true), nowMs = 14_000))
        assertFalse(watchdog.shouldTeardown(snapshot(lastPongAtMs = 1_000, alreadyClosing = true), nowMs = 20_000))
    }

    @Test
    fun `watchdog - no verdict without a socket, connection, or completed handshake`() {
        val watchdog = KeepaliveWatchdog()
        assertFalse(watchdog.shouldTeardown(snapshot(connected = false, lastPongAtMs = 0), nowMs = 60_000))
        assertFalse(watchdog.shouldTeardown(snapshot(socketPresent = false, lastPongAtMs = 0), nowMs = 60_000))
        assertFalse(watchdog.shouldTeardown(snapshot(handshakeCompleted = false, lastPongAtMs = 0), nowMs = 60_000))
        // A brand-new connection that never received a pong (lastPongAt <= 0) is not judged dead.
        assertFalse(watchdog.shouldTeardown(snapshot(lastPongAtMs = -1), nowMs = 60_000))
    }

    // ---------------------------------------------------------------- stuck-dial backstop

    @Test
    fun `stuck dial backstop - socket-less CONNECTING past deadline plus grace fails`() {
        val dialAt = 10_000L
        assertFalse(StuckDialDetector.isStuck(P2pConnState.CONNECTING, socketPresent = false, dialAtMs = dialAt, nowMs = dialAt + 6_999))
        assertTrue(StuckDialDetector.isStuck(P2pConnState.CONNECTING, socketPresent = false, dialAtMs = dialAt, nowMs = dialAt + 7_000))
    }

    @Test
    fun `stuck dial backstop - never fires with a socket, another state, or no dial`() {
        val now = 100_000L
        assertFalse(StuckDialDetector.isStuck(P2pConnState.CONNECTING, socketPresent = true, dialAtMs = 0, nowMs = now))
        assertFalse(StuckDialDetector.isStuck(P2pConnState.RECONNECTING, socketPresent = false, dialAtMs = 0, nowMs = now))
        assertFalse(StuckDialDetector.isStuck(P2pConnState.CONNECTING, socketPresent = false, dialAtMs = 0, nowMs = now))
        assertFalse(StuckDialDetector.isStuck(P2pConnState.CONNECTED, socketPresent = false, dialAtMs = 10_000, nowMs = now))
    }

    // ---------------------------------------------------------------- discovery churn gate

    @Test
    fun `churn gate - removals inside the browse quiet period are ignored`() {
        val gate = DiscoveryChurnGate()
        gate.onBrowseStarted(nowMs = 1_000)
        assertTrue(gate.inBrowseQuietPeriod(1_000 + 29_999))
        assertFalse(gate.inBrowseQuietPeriod(1_000 + 30_000))
        assertFalse(gate.inBrowseQuietPeriod(1_000 + 40_000))
        // New browse restarts the quiet window (45 s re-browse cycle).
        gate.onBrowseStarted(nowMs = 46_000)
        assertTrue(gate.inBrowseQuietPeriod(50_000))
    }

    @Test
    fun `churn gate - debounced removal fires only past 2_5s and is healed by re-found`() {
        val gate = DiscoveryChurnGate()
        gate.onBrowseStarted(nowMs = 0)
        var gonePeer: String? = null
        // Outside the quiet period so the debounce path applies.
        val confirm = checkNotNull(
            gate.scheduleRemoval("musicshare-abc._musicshare._tcp.local.", "abc", 40_000) { gonePeer = it },
        )
        assertEquals(null, gonePeer) // nothing fired yet
        // Heal: a found/resolved within the debounce window cancels the removal.
        assertTrue(gate.heal("musicshare-abc._musicshare._tcp.local."))
        confirm()
        assertNull(gonePeer) // healed -> never fires
    }

    @Test
    fun `churn gate - unhealed debounced removal fires once`() {
        val gate = DiscoveryChurnGate()
        gate.onBrowseStarted(nowMs = 0)
        var goneCount = 0
        val confirm = checkNotNull(gate.scheduleRemoval("inst", "peer-1", 40_000) { goneCount++ })
        // A pending removal for the same instance cannot be double-scheduled (earliest wins).
        assertNull(gate.scheduleRemoval("inst", "peer-1", 40_500) { goneCount++ })
        assertEquals(0, goneCount)
        confirm() // fires: entry was pending
        confirm() // second run is a no-op (already consumed)
        assertEquals(1, goneCount)
        // After the entry was consumed a fresh removal can be scheduled again.
        val confirm2 = checkNotNull(gate.scheduleRemoval("inst", "peer-1", 42_000) { goneCount++ })
        confirm2()
        assertEquals(2, goneCount)
    }

    @Test
    fun `churn gate - never schedules for an unresolved instance`() {
        val gate = DiscoveryChurnGate()
        gate.onBrowseStarted(nowMs = 0)
        assertNull(gate.scheduleRemoval("unknown-instance", peerId = null, observedAtMs = 40_000) {})
    }
}
