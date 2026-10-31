package com.theveloper.pixelplay.data.p2p

import com.theveloper.pixelplay.data.model.Song

/**
 * Player-side shared-session policy (contract section 8: "players own policy; the protocol
 * owns state and violation detection"). The engine invokes it from the protocol threads;
 * implementations must dispatch player calls to the thread their player requires.
 */
interface P2pSessionPolicy {

    /**
     * A session-capable peer is now (or still) ACTIVE with a playing stint. Recommended
     * default policy: if self is playing, pause self (pause-on-peer-active).
     */
    fun onPeerBecameActive(
        peerId: String,
        peerName: String,
        trackHash: String,
        trackTitle: String,
        positionSec: Double,
        playbackId: String,
    )

    /**
     * The user asked to resume the peer's stint here. [song] is the local file resolved from
     * the peer's trackHash. Implementations start local playback at [positionSec]; the engine
     * stamps a fresh playbackId either way. Return false when local playback could not be
     * started (no player session) — the takeover then did not happen and stays silent.
     */
    fun onTakeoverRequested(song: Song, positionSec: Double): Boolean
}
