package com.theveloper.pixelplay.data.p2p

import android.os.Handler
import android.os.Looper
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.viewmodel.PlaybackStateHolder
import com.theveloper.pixelplay.utils.MediaItemBuilder
import javax.inject.Singleton
import timber.log.Timber

/**
 * PixelPlayer's shared-session policy (contract section 8: "players own policy").
 *
 *  - pause-on-peer-active (the reference default): when a session-capable peer holds the
 *    active stint and self is playing, pause self. The protocol never requires this.
 *  - takeover: start local playback of the hash-resolved local file at the peer's
 *    positionSec via the app-wide MediaController (`setMediaItem(item, positionMs)` +
 *    prepare + play), so the user resumes the same track from their own copy.
 */
@Singleton
class PauseOnPeerActivePolicy(
    private val playbackStateHolder: PlaybackStateHolder,
    private val dispatch: (Runnable) -> Unit = { runnable ->
        Handler(Looper.getMainLooper()).post(runnable)
    },
) : P2pSessionPolicy {

    override fun onPeerBecameActive(
        peerId: String,
        peerName: String,
        trackHash: String,
        trackTitle: String,
        positionSec: Double,
        playbackId: String,
    ) {
        val controller = playbackStateHolder.mediaController ?: return
        if (!controller.isPlaying) return
        dispatch {
            if (controller.isPlaying) {
                controller.pause()
                Timber.tag("P2P").i("session policy: paused self — peer %s is now active (%s)", peerName, trackTitle)
            }
        }
    }

    override fun onTakeoverRequested(song: Song, positionSec: Double): Boolean {
        val controller = playbackStateHolder.mediaController ?: return false
        dispatch {
            val item = MediaItemBuilder.build(song)
            controller.setMediaItem(item, (positionSec * 1000.0).toLong().coerceAtLeast(0L))
            controller.prepare()
            controller.play()
            Timber.tag("P2P").i(
                "session takeover: playing %s @%.1fs from local copy",
                song.title, positionSec,
            )
        }
        return true
    }
}
