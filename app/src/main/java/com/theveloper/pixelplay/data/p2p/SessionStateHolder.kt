package com.theveloper.pixelplay.data.p2p

import com.theveloper.pixelplay.shared.p2p.P2pPlaybackStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Reachability of the peer that owns the remote stint (connection axis, not session axis). */
enum class SessionPeerReachability { CONNECTED, UNREACHABLE, GONE }

/**
 * Authoritative last-known data of a playback stint. The holder NEVER stores
 * dead-reckoned values: positionSec is exactly what the stint owner last reported,
 * [updatedAtMs] says when. Any elapsed-time extrapolation is a presentation estimate
 * confined to the UI.
 */
data class StintSnapshot(
    val trackHash: String,
    val trackTitle: String,
    val positionSec: Double,
    val playbackId: String,
    val status: P2pPlaybackStatus,
    val updatedAtMs: Long,
)

/** The shared-session view: whose stint is represented on the player surface. */
sealed interface SessionSource {
    /** This device holds the stint (LOCAL wins the moment self starts one with a hash). */
    data class Local(val stint: StintSnapshot) : SessionSource

    /**
     * A peer holds the stint. [reachability] is INDEPENDENT of session existence: a
     * disconnected peer keeps the stint visible as last-seen (UNREACHABLE) or gone
     * (GONE) — connectivity loss never silently falls back to local playback.
     */
    data class Remote(
        val peerId: String,
        val peerName: String,
        val reachability: SessionPeerReachability,
        val stint: StintSnapshot,
    ) : SessionSource

    /** A takeover has been initiated and has not yet been resolved by self's playing broadcast. */
    data class TakeoverInFlight(
        val peerId: String,
        val peerName: String,
        val trackHash: String,
        val positionSec: Double,
    ) : SessionSource
}

data class ActivePeerSession(
    val peerId: String,
    val peerName: String,
    val trackHash: String,
    val trackTitle: String,
    val positionSec: Double,
    val playbackId: String,
    val updatedAtMs: Long,
)

/** Contract section 8 violation event — the protocol never auto-resolves it. */
data class SessionConflictEvent(
    val detectedAtMs: Long,
    val deviceIds: List<String>,
    val deviceNames: List<String>,
)

/** Contract section 8: a takeover whose trackHash was not in the local index. Silent on the wire. */
data class LibraryUnsyncedEvent(
    val atMs: Long,
    val peerName: String,
    val trackHash: String,
)

data class P2pSessionState(
    /** The session view the player surface represents; null = no session (pure local, no stint view). */
    val source: SessionSource? = null,
    /** Session-capable connected peers = latest state_update carried a trackHash. */
    val sessionCapablePeerIds: Set<String> = emptySet(),
    /** Ongoing single-active violation (visible past the 2 s handoff grace). */
    val conflictActive: Boolean = false,
    val conflicts: List<SessionConflictEvent> = emptyList(),
    val lastUnsynced: LibraryUnsyncedEvent? = null,
) {
    /** Convenience for the UI: the remote peer of the current view, when the source is remote. */
    val remotePeer: SessionSource.Remote?
        get() = source as? SessionSource.Remote
}

/** UI-facing shared-session state; written only by [PeerProtocolEngine]. */
@Singleton
class SessionStateHolder @Inject constructor() {

    private val _state = MutableStateFlow(P2pSessionState())
    val state: StateFlow<P2pSessionState> = _state.asStateFlow()

    fun update(transform: (P2pSessionState) -> P2pSessionState) {
        _state.value = transform(_state.value)
    }

    fun clear() {
        _state.value = P2pSessionState()
    }
}
