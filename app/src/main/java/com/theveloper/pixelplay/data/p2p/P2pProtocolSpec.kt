package com.theveloper.pixelplay.data.p2p

/**
 * Protocol constants, one-to-one with docs/PROTOCOL.md (frozen contract).
 */
object P2pProtocolSpec {
    const val SERVICE_TYPE = "_musicshare._tcp."
    const val TXT_ID = "id"
    const val TXT_NAME = "name"
    const val TXT_V = "v"
    const val PROTOCOL_VERSION = "1"
    const val PLATFORM = "android"

    // Close codes (diagnostic convention; receivers always log code + reason).
    const val CLOSE_NORMAL = 1000
    const val CLOSE_HANDSHAKE_TIMEOUT = 4002
    const val CLOSE_SUPERSEDED = 4003
    const val CLOSE_INTERNAL_ERROR = 1011

    // Timing (ms).
    const val HANDSHAKE_TIMEOUT_MS = 5_000L
    const val DIAL_TIMEOUT_MS = 5_000L
    const val PING_INTERVAL_MS = 5_000L
    const val PONG_TIMEOUT_MS = 10_000L
    const val PEER_EXPIRY_MS = 60_000L

    // Reconnect delays: 1.5 -> 30 s cap, +/-20% jitter, reset on success.
    val BACKOFF_STEPS_MS = longArrayOf(1_500, 3_000, 6_000, 12_000, 24_000, 30_000)
    const val BACKOFF_JITTER = 0.20

    // Discovery churn defenses (measured on API 37 NsdManager; see reference REPORT.md defects 6).
    const val REBROWSE_INTERVAL_MS = 45_000L
    const val REBROWSE_RESTART_DELAY_MS = 400L
    const val GONE_CONFIRM_MS = 2_500L
    const val BROWSE_QUIET_MS = 30_000L

    // Receiver bookkeeping (contract section 6).
    const val MESSAGE_ID_CACHE_SIZE = 128

    // Outbound state cadence.
    const val STATE_BROADCAST_PERIOD_MS = 1_000L
}
