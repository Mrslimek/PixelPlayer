package com.theveloper.pixelplay.data.p2p

import android.os.Handler
import android.os.Looper
import com.theveloper.pixelplay.presentation.viewmodel.PlaybackStateHolder
import com.theveloper.pixelplay.shared.p2p.P2pCommandAction
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * Applies remote `command` actions (contract section 6) to the app-wide [androidx.media3.session.MediaController]
 * held by [PlaybackStateHolder] — the same controller instance MediaControllerSyncStateHolder
 * syncs the UI from. Player calls are dispatched to the main looper as MediaController requires.
 *
 * Action mapping (documented decision):
 *  - play/pause/next/previous map 1:1 to the controller.
 *  - toggle: pause when playing, otherwise play.
 *  - stop: pause + seekTo(0) — a reversible "stop at start of track". Player.stop() would
 *    move the session to STATE_IDLE, dropping the mediaPlayback foreground service and the
 *    queue position, which is not what a remote "stop" means for a music player.
 *  - seek: positionSec (REQUIRED by the contract) * 1000 -> seekTo(ms).
 *
 * Returns false when there is no local player session (engine logs command_ignored
 * reason=unsupported); the effect itself is observable only via the subsequent state broadcast.
 */
@Singleton
class MediaControllerCommandExecutor(
    private val playbackStateHolder: PlaybackStateHolder,
    private val dispatch: (Runnable) -> Unit = { runnable ->
        Handler(Looper.getMainLooper()).post(runnable)
    },
) : P2pCommandExecutor {

    override fun execute(action: P2pCommandAction, positionSec: Double?): Boolean {
        // Contract: positionSec is REQUIRED for seek; a missing value cannot be applied.
        if (action == P2pCommandAction.SEEK && positionSec == null) return false
        val controller = playbackStateHolder.mediaController ?: return false
        dispatch {
            when (action) {
                P2pCommandAction.PLAY -> controller.play()
                P2pCommandAction.PAUSE -> controller.pause()
                P2pCommandAction.TOGGLE -> if (controller.isPlaying) controller.pause() else controller.play()
                P2pCommandAction.STOP -> {
                    controller.pause()
                    controller.seekTo(0L)
                }
                P2pCommandAction.SEEK -> {
                    val seconds = positionSec ?: return@dispatch
                    controller.seekTo((seconds * 1000.0).roundToLong().coerceAtLeast(0L))
                }
                P2pCommandAction.NEXT -> controller.seekToNext()
                P2pCommandAction.PREVIOUS -> controller.seekToPrevious()
            }
        }
        return true
    }
}
