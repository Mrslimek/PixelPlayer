package com.theveloper.pixelplay.presentation.viewmodel

import timber.log.Timber
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.p2p.SessionSource
import com.theveloper.pixelplay.shared.p2p.P2pPlaybackStatus
import com.theveloper.pixelplay.shared.p2p.P2pRepeatMode

/**
 * What the player surface should render for the current [SessionSource]. A null
 * [sessionKey] means local pass-through; otherwise the surface renders the remote
 * stint resolved from the local library by [trackHash].
 */
internal data class SessionDisplayRequest(
    val sessionKey: String?,
    val trackHash: String?,
    val trackTitle: String?,
)

/**
 * Pure display-source logic for the shared session (contract section 8). Resolves the
 * remote stint's trackHash against the local library and caches per session key so an
 * UNREACHABLE/GONE peer keeps showing the last resolved song without re-resolving on
 * every state tick. Presentation-only: nothing here writes playback state.
 */
internal class SessionDisplaySource(
    private val songForHash: suspend (String) -> Song?,
) {

    private val resolvedBySessionKey = mutableMapOf<String, Song>()
    private val missedLogKeys = mutableSetOf<String>()

    fun requestFor(source: SessionSource?): SessionDisplayRequest = when (source) {
        is SessionSource.Remote -> SessionDisplayRequest(
            sessionKey = "${source.stint.playbackId}|${source.stint.trackHash}",
            trackHash = source.stint.trackHash,
            trackTitle = source.stint.trackTitle,
        )
        is SessionSource.TakeoverInFlight -> SessionDisplayRequest(
            sessionKey = "takeover|${source.trackHash}",
            trackHash = source.trackHash,
            trackTitle = null,
        )
        else -> SessionDisplayRequest(sessionKey = null, trackHash = null, trackTitle = null)
    }

    suspend fun resolveSong(request: SessionDisplayRequest, localSong: Song?): Song? {
        val sessionKey = request.sessionKey ?: return localSong
        resolvedBySessionKey[sessionKey]?.let { return it }
        val trackHash = request.trackHash ?: return localSong
        // NO negative caching: a miss (typically a cold hash index, warm-up takes tens of
        // seconds) returns the placeholder and retries on the next session-state emission
        // until the library actually resolves the hash.
        val resolved = songForHash(trackHash)
        if (resolved != null) {
            resolvedBySessionKey[sessionKey] = resolved
            Timber.tag(TAG).i("display resolved hash=${trackHash.take(8)} -> ${resolved.title}")
            missedLogKeys.remove(sessionKey)
            return resolved
        }
        if (missedLogKeys.add(sessionKey)) {
            Timber.tag(TAG).i("display resolve miss (library_unsynced?) hash=${trackHash.take(8)}")
        }
        return placeholderSong(request.trackTitle, trackHash)
    }

    fun isPlaying(source: SessionSource?, localIsPlaying: Boolean): Boolean = when (source) {
        is SessionSource.Remote -> source.stint.status == P2pPlaybackStatus.PLAYING
        is SessionSource.TakeoverInFlight -> false
        else -> localIsPlaying
    }

    fun isSessionActive(source: SessionSource?): Boolean =
        source is SessionSource.Remote || source is SessionSource.TakeoverInFlight

    /** The remote stint's shuffle mode; null when local/null source or the peer did not report it. */
    fun shuffleMode(source: SessionSource?): Boolean? =
        (source as? SessionSource.Remote)?.stint?.shuffle

    /** The remote stint's repeat mode; null when local/null source or the peer did not report it. */
    fun repeatMode(source: SessionSource?): P2pRepeatMode? =
        (source as? SessionSource.Remote)?.stint?.repeat

    /**
     * Optimistic session-mode override (contract section 6 UX): a tap shows the EXPECTED
     * mode immediately instead of waiting for the peer's broadcast round-trip. Keyed by
     * the stint's playbackId so it never leaks across stints; a broadcast that reports
     * the field always overwrites (retires) the optimistic value — reconciliation is
     * strictly truthful.
     */
    data class OptimisticModes(val playbackId: String, val shuffle: Boolean?, val repeat: P2pRepeatMode?)

    /** The value the surface shows: optimistic (for the same stint) over the last report. */
    fun shownShuffle(reported: Boolean?, optimistic: OptimisticModes?, playbackId: String?): Boolean? =
        if (optimistic != null && optimistic.playbackId == playbackId) optimistic.shuffle ?: reported
        else reported

    fun shownRepeat(reported: P2pRepeatMode?, optimistic: OptimisticModes?, playbackId: String?): P2pRepeatMode? =
        if (optimistic != null && optimistic.playbackId == playbackId) optimistic.repeat ?: reported
        else reported

    /**
     * Reconcile after a session-state emission: a broadcast always overwrites — retire the
     * optimistic field as soon as the peer reports that field for the same stint. Drop the
     * whole override when the view leaves the reported stint (local / new stint / none).
     */
    fun reconcile(optimistic: OptimisticModes?, source: SessionSource?): OptimisticModes? {
        if (optimistic == null) return null
        val remote = source as? SessionSource.Remote ?: return null
        if (remote.stint.playbackId != optimistic.playbackId) return null
        var next = optimistic
        if (remote.stint.shuffle != null) next = next.copy(shuffle = null)
        if (remote.stint.repeat != null) next = next.copy(repeat = null)
        return next.takeIf { it.shuffle != null || it.repeat != null }
    }

    companion object {
        private const val TAG = "P2P"
        /** Library-unsynced fallback: titled by the stint report, no artwork, unknown duration. */
        fun placeholderSong(trackTitle: String?, trackHash: String): Song = Song(
            id = "p2p_$trackHash",
            title = trackTitle.orEmpty(),
            artist = "",
            artistId = 0L,
            album = "",
            albumId = 0L,
            path = "",
            contentUriString = "",
            albumArtUriString = null,
            duration = 0L,
            mimeType = null,
            bitrate = null,
            sampleRate = null,
        )
    }
}
