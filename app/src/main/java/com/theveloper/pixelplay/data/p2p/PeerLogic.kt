package com.theveloper.pixelplay.data.p2p

import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Pure protocol logic extracted from [PeerProtocolEngine] so every measured
 * defect guard of the reference MVP (see music_share_protocol/docs/REPORT.md,
 * "Defects found & fixed" 1-11) is unit-testable with a fake clock.
 */

/** Contract section 3: after resolving a peer, the lexicographically smaller device id dials. */
object P2pTieBreak {
    fun shouldDial(myId: String, peerId: String): Boolean = myId.isNotEmpty() && myId < peerId
}

/**
 * Contract section 5: delays 1.5 s, 3 s, 6 s, 12 s, 24 s, capped at 30 s, each +/-20% jitter,
 * reset after success. [nextDelayMs] escalates across consecutive failures; [reset] is called
 * ONLY on a successful handshake — never on a scheduling event.
 */
class BackoffLadder(
    private val stepsMs: LongArray = P2pProtocolSpec.BACKOFF_STEPS_MS,
    private val jitterFraction: Double = P2pProtocolSpec.BACKOFF_JITTER,
    private val random: Random = Random.Default,
) {
    var attempts = 0
        private set

    val currentBaseMs: Long get() = stepsMs[attempts.coerceAtMost(stepsMs.lastIndex)]

    fun nextDelayMs(): Long {
        val base = currentBaseMs.toDouble()
        attempts = (attempts + 1).coerceAtMost(stepsMs.lastIndex)
        if (jitterFraction <= 0.0) return base.toLong()
        val factor = 1.0 + random.nextDouble(-jitterFraction, jitterFraction)
        return (base * factor).roundToLong().coerceAtLeast(1L)
    }

    fun reset() {
        attempts = 0
    }
}

/**
 * Defect-5 escalation backstop: a dial whose HTTP upgrade never completed must fail at
 * ~DIAL_TIMEOUT_MS so dead dials always escalate the reconnect ladder. The transport-level
 * dial timeout usually delivers this; this predicate lets the 1 s tick guarantee it even if
 * that delivery is lost, by catching a socket-less CONNECTING record past its deadline.
 */
object StuckDialDetector {
    const val GRACE_MS = 2_000L

    fun isStuck(state: P2pConnState, socketPresent: Boolean, dialAtMs: Long, nowMs: Long): Boolean {
        if (state != P2pConnState.CONNECTING) return false
        if (socketPresent) return false
        if (dialAtMs <= 0L) return false
        return nowMs - dialAtMs >= P2pProtocolSpec.DIAL_TIMEOUT_MS + GRACE_MS
    }
}

/** Contract section 6: `id` already seen within the last 128 received ids -> message_duplicate. */
class MessageIdCache(private val capacity: Int = P2pProtocolSpec.MESSAGE_ID_CACHE_SIZE) {
    private val order = ArrayDeque<String>(capacity)
    private val seen = HashSet<String>()

    fun isDuplicate(id: String): Boolean = id.isNotEmpty() && seen.contains(id)

    fun remember(id: String) {
        if (id.isEmpty()) return
        seen.add(id)
        order.addLast(id)
        while (order.size > capacity) seen.remove(order.removeFirst())
    }
}

/**
 * Contract section 6: per-device monotonic `seq`. If arriving seq <= last seen seq from that
 * peer, the arrival is a `state_conflict` anomaly (recorded; newest arrival is still displayed).
 */
class SeqTracker {
    var lastSeq: Long = -1L
        private set
    var lastRecvAtMs: Long = 0L
        private set

    fun onArrival(arrivingSeq: Long, nowMs: Long): Boolean {
        val conflict = lastSeq >= 0L && arrivingSeq <= lastSeq
        lastSeq = arrivingSeq
        lastRecvAtMs = nowMs
        return conflict
    }
}

/**
 * Section 4 failure verdict for one live socket. Keyed on LAST RECEIVED PONG — never on
 * ping-send time, which refreshes every <=5 s and can never reach the 10 s threshold
 * (reference defect 2). [LivenessSnapshot.alreadyClosing] enforces the one-shot teardown
 * latch (defect 10): once teardown is decided, later ticks get no verdict.
 */
class KeepaliveWatchdog(private val pongTimeoutMs: Long = P2pProtocolSpec.PONG_TIMEOUT_MS) {

    data class LivenessSnapshot(
        val connected: Boolean,
        val socketPresent: Boolean,
        val alreadyClosing: Boolean,
        val handshakeCompleted: Boolean,
        val lastPongAtMs: Long,
    )

    fun shouldTeardown(snapshot: LivenessSnapshot, nowMs: Long): Boolean {
        if (!snapshot.connected) return false
        if (!snapshot.socketPresent) return false
        if (snapshot.alreadyClosing) return false
        if (!snapshot.handshakeCompleted) return false
        if (snapshot.lastPongAtMs <= 0L) return false
        return nowMs - snapshot.lastPongAtMs >= pongTimeoutMs
    }
}

/**
 * Discovery churn defenses for the API 37 NsdManager behavior measured in the reference MVP:
 * a 2.5 s removal debounce (spurious lost->found heals within the same browse generation) and
 * a 30 s quiet period after each browse start during which removals are ignored outright
 * (reference defect 6, round-3b). The 60 s no-socket expiry stays the backstop for real
 * departures, so trading detection latency here never trades correctness.
 */
class DiscoveryChurnGate(
    private val goneConfirmMs: Long = P2pProtocolSpec.GONE_CONFIRM_MS,
    private val browseQuietMs: Long = P2pProtocolSpec.BROWSE_QUIET_MS,
) {
    data class PendingGone(val peerId: String, val observedAtMs: Long, val confirm: () -> Unit)

    private var browseStartMs = 0L
    private val pending = HashMap<String, PendingGone>()

    fun onBrowseStarted(nowMs: Long) {
        browseStartMs = nowMs
    }

    /** True when a removal arriving at [nowMs] falls inside the current browse's quiet period. */
    fun inBrowseQuietPeriod(nowMs: Long): Boolean =
        nowMs - browseStartMs < browseQuietMs

    /**
     * Schedules a debounced removal for [instance]. The caller receives the confirm callback
     * to post with its own scheduler; running it past [goneConfirmMs] fires [onGone] unless
     * [heal] removed the entry first. Returns null when the instance already has a pending
     * removal (the earliest timestamp wins) or when no resolved peer id is known for it.
     */
    fun scheduleRemoval(instance: String, peerId: String?, observedAtMs: Long, onGone: (String) -> Unit): (() -> Unit)? {
        if (pending.containsKey(instance)) return null
        if (peerId == null) return null
        val confirm = { if (pending.remove(instance) != null) onGone(peerId) }
        pending[instance] = PendingGone(peerId, observedAtMs, confirm)
        return confirm
    }

    /** A found/resolved service cancels a pending removal: it is still alive. */
    fun heal(instance: String): Boolean = pending.remove(instance) != null

    fun clear() = pending.clear()
}
