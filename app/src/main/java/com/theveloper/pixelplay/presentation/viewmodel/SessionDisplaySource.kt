package com.theveloper.pixelplay.presentation.viewmodel

import timber.log.Timber
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.p2p.SessionSource
import com.theveloper.pixelplay.shared.p2p.P2pPlaybackStatus

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
