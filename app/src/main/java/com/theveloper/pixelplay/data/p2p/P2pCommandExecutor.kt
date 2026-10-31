package com.theveloper.pixelplay.data.p2p

import com.theveloper.pixelplay.shared.p2p.P2pCommandAction

/**
 * Applies a remote playback-control command (contract section 6 `command`) to the local
 * player. Implementations run off the protocol threads and MUST dispatch player calls to
 * the thread the player session requires (typically main).
 *
 * Returns false when the action cannot be applied locally (no player session), which the
 * engine records as `command_ignored reason=unsupported`. Action mapping ("stop" = pause +
 * seek to 0:00, "toggle" = pause when playing else play) lives in the implementation.
 */
fun interface P2pCommandExecutor {
    fun execute(action: P2pCommandAction, positionSec: Double?): Boolean
}
