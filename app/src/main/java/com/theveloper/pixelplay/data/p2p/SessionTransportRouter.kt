package com.theveloper.pixelplay.data.p2p

import com.theveloper.pixelplay.shared.p2p.P2pCommandAction
import com.theveloper.pixelplay.shared.p2p.P2pPlaybackStatus
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Routes main-player transport actions depending on the shared-session view
 * (contract section 8 + product rule: CONNECTION and SESSION are different axes).
 *
 *  - LOCAL (no remote view / local stint): the caller runs its local behavior.
 *  - REMOTE + CONNECTED: transport is dispatched to the stint owner as `command`
 *    messages (fire-and-forget; the effect arrives via their state_update broadcast).
 *  - REMOTE + UNREACHABLE/GONE: DISABLED — controls neither go local (a connectivity
 *    loss must never silently switch the player to local playback) nor send commands
 *    into a dead socket. Take-over stays available through [PeerProtocolEngine.playHere].
 *  - TAKEOVER_IN_FLIGHT: transport is swallowed until the takeover resolves.
 */
@Singleton
class SessionTransportRouter @Inject constructor(
    private val sessionStateHolder: SessionStateHolder,
    private val engine: PeerProtocolEngine,
) {

    enum class TransportMode { LOCAL, REMOTE, DISABLED, IN_FLIGHT }

    fun transportMode(): TransportMode = when (val source = sessionStateHolder.state.value.source) {
        is SessionSource.Remote ->
            if (source.reachability == SessionPeerReachability.CONNECTED) TransportMode.REMOTE else TransportMode.DISABLED
        is SessionSource.TakeoverInFlight -> TransportMode.IN_FLIGHT
        null, is SessionSource.Local -> TransportMode.LOCAL
    }

    /** The remote stint currently represented on the player surface, when remote. */
    fun remoteView(): SessionSource.Remote? = sessionStateHolder.state.value.remotePeer

    /** play/pause: derived from the stint's last-known status. Returns false when not remote. */
    fun dispatchTogglePlayPause(): Boolean {
        val source = remoteView() ?: return false
        val action = if (source.stint.status == P2pPlaybackStatus.PLAYING) {
            P2pCommandAction.PAUSE
        } else {
            P2pCommandAction.PLAY
        }
        engine.sendCommand(source.peerId, action)
        Timber.tag("P2P").d("transport routed to %s: %s", source.peerName, action.wireValue)
        return true
    }

    fun dispatchSeek(positionSec: Double): Boolean {
        val source = remoteView() ?: return false
        engine.sendCommand(source.peerId, P2pCommandAction.SEEK, positionSec)
        return true
    }

    fun dispatchSkip(next: Boolean): Boolean {
        val source = remoteView() ?: return false
        engine.sendCommand(source.peerId, if (next) P2pCommandAction.NEXT else P2pCommandAction.PREVIOUS)
        return true
    }

    /** Playback-mode commands (contract section 6, added 2026-11-02). Returns false when not remote. */
    fun dispatchShuffle(enabled: Boolean): Boolean {
        val source = remoteView() ?: return false
        engine.sendCommand(source.peerId, if (enabled) P2pCommandAction.SHUFFLE_ON else P2pCommandAction.SHUFFLE_OFF)
        return true
    }

    fun dispatchRepeat(action: P2pCommandAction): Boolean {
        val source = remoteView() ?: return false
        engine.sendCommand(source.peerId, action)
        return true
    }
}
