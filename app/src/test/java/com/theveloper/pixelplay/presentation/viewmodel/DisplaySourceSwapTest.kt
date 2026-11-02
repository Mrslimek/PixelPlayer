package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.p2p.SessionPeerReachability
import com.theveloper.pixelplay.data.p2p.SessionSource
import com.theveloper.pixelplay.data.p2p.StintSnapshot
import com.theveloper.pixelplay.shared.p2p.P2pPlaybackStatus
import com.theveloper.pixelplay.shared.p2p.P2pRepeatMode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the presentation-only display-source swap: the player surface renders the
 * remote stint's locally resolved Song / play state while a session is active, and
 * passes the local state straight through otherwise.
 */
class DisplaySourceSwapTest {

    private val dbSong = Song(
        id = "42",
        title = "Real Title",
        artist = "Real Artist",
        artistId = 7L,
        album = "Real Album",
        albumId = 9L,
        path = "/music/real.flac",
        contentUriString = "content://media/42",
        albumArtUriString = "content://art/42",
        duration = 213_000L,
        mimeType = "audio/flac",
        bitrate = 900_000,
        sampleRate = 44_100,
    )

    private class CountingLookup(val result: Song?) {
        var calls = 0
        suspend fun lookup(trackHash: String): Song? {
            calls += 1
            return result
        }
    }

    private fun remoteStint(
        status: P2pPlaybackStatus,
        reachability: SessionPeerReachability = SessionPeerReachability.CONNECTED,
        trackHash: String = "hash-1",
        playbackId: String = "pb-1",
    ) = SessionSource.Remote(
        peerId = "peer-a",
        peerName = "Phone",
        reachability = reachability,
        stint = StintSnapshot(
            trackHash = trackHash,
            trackTitle = "Reported Title",
            positionSec = 12.5,
            playbackId = playbackId,
            status = status,
            updatedAtMs = 1_000L,
        ),
    )

    @Test
    fun `remote + resolvable hash resolves the library song`() = runTest {
        val lookup = CountingLookup(dbSong)
        val source = SessionDisplaySource { lookup.lookup(it) }
        val request = source.requestFor(remoteStint(P2pPlaybackStatus.PLAYING))

        val song = source.resolveSong(request, localSong = null)

        assertEquals(dbSong, song)
    }

    @Test
    fun `remote + unresolvable hash falls back to a placeholder with the stint title`() = runTest {
        val source = SessionDisplaySource { null }
        val request = source.requestFor(remoteStint(P2pPlaybackStatus.PLAYING))

        val song = source.resolveSong(request, localSong = null)

        assertEquals("Reported Title", song?.title)
        assertNull(song?.albumArtUriString)
        assertEquals(0L, song?.duration)
    }

    @Test
    fun `unreachable then gone keeps the last resolved song without re-resolving`() = runTest {
        val lookup = CountingLookup(dbSong)
        val source = SessionDisplaySource { lookup.lookup(it) }

        val connected = source.requestFor(remoteStint(P2pPlaybackStatus.PLAYING))
        val first = source.resolveSong(connected, localSong = null)

        val unreachable = source.requestFor(
            remoteStint(P2pPlaybackStatus.PLAYING, reachability = SessionPeerReachability.UNREACHABLE),
        )
        val gone = source.requestFor(
            remoteStint(P2pPlaybackStatus.PLAYING, reachability = SessionPeerReachability.GONE),
        )
        val second = source.resolveSong(unreachable, localSong = null)
        val third = source.resolveSong(gone, localSong = null)

        assertEquals(first, second)
        assertEquals(first, third)
        assertEquals(1, lookup.calls)
    }

    @Test
    fun `displayIsPlaying follows the stint status across reachability changes`() {
        val source = SessionDisplaySource { null }

        assertTrue(source.isPlaying(remoteStint(P2pPlaybackStatus.PLAYING), localIsPlaying = false))
        assertFalse(source.isPlaying(remoteStint(P2pPlaybackStatus.PAUSED), localIsPlaying = true))
        assertFalse(
            source.isPlaying(
                remoteStint(P2pPlaybackStatus.PAUSED, reachability = SessionPeerReachability.GONE),
                localIsPlaying = true,
            ),
        )
    }

    @Test
    fun `takeover in flight resolves by its trackHash and reports not playing`() = runTest {
        val lookup = CountingLookup(dbSong)
        val source = SessionDisplaySource { lookup.lookup(it) }
        val takeover = SessionSource.TakeoverInFlight(
            peerId = "peer-a",
            peerName = "Phone",
            trackHash = "hash-1",
            positionSec = 3.0,
        )

        val request = source.requestFor(takeover)
        val song = source.resolveSong(request, localSong = null)

        assertEquals(dbSong, song)
        assertEquals("hash-1", request.trackHash)
        assertFalse(source.isPlaying(takeover, localIsPlaying = true))
    }

    @Test
    fun `local and null sources pass the local song and play state through`() = runTest {
        val lookup = CountingLookup(dbSong)
        val source = SessionDisplaySource { lookup.lookup(it) }

        val localRequest = source.requestFor(SessionSource.Local(remoteStint(P2pPlaybackStatus.PLAYING).stint))
        val nullRequest = source.requestFor(null)

        assertEquals(null, localRequest.sessionKey)
        assertEquals(null, nullRequest.sessionKey)
        assertEquals(dbSong, source.resolveSong(localRequest, localSong = dbSong))
        assertNull(source.resolveSong(nullRequest, localSong = null))
        assertEquals(0, lookup.calls)
        assertTrue(source.isPlaying(null, localIsPlaying = true))
        assertFalse(source.isPlaying(null, localIsPlaying = false))
    }

    @Test
    fun `sessionActive is true for remote and takeover regardless of reachability`() {
        val source = SessionDisplaySource { null }

        assertTrue(source.isSessionActive(remoteStint(P2pPlaybackStatus.PLAYING)))
        assertTrue(source.isSessionActive(remoteStint(P2pPlaybackStatus.PLAYING, reachability = SessionPeerReachability.UNREACHABLE)))
        assertTrue(source.isSessionActive(remoteStint(P2pPlaybackStatus.PLAYING, reachability = SessionPeerReachability.GONE)))
        assertTrue(
            source.isSessionActive(
                SessionSource.TakeoverInFlight("peer-a", "Phone", "hash-1", 0.0),
            ),
        )
        assertFalse(source.isSessionActive(SessionSource.Local(remoteStint(P2pPlaybackStatus.PLAYING).stint)))
        assertFalse(source.isSessionActive(null))
    }

    @Test
    fun `a new playbackId re-resolves instead of reusing the stale stint song`() = runTest {
        val lookup = CountingLookup(dbSong)
        val source = SessionDisplaySource { lookup.lookup(it) }

        val first = source.resolveSong(source.requestFor(remoteStint(P2pPlaybackStatus.PLAYING)), null)
        val second = source.resolveSong(
            source.requestFor(remoteStint(P2pPlaybackStatus.PLAYING, playbackId = "pb-2")),
            null,
        )

        assertEquals(first, second)
        assertEquals(2, lookup.calls)
        assertNotEquals(1, lookup.calls)
    }

    @Test
    fun `a miss is not negatively cached - later emission retries and gets the real song`() = runTest {
        var result: Song? = null
        var calls = 0
        val source = SessionDisplaySource {
            calls += 1
            result
        }
        val request = source.requestFor(remoteStint(P2pPlaybackStatus.PLAYING))

        // Cold hash index: first resolution misses -> placeholder, nothing cached.
        val placeholder = source.resolveSong(request, localSong = null)
        assertEquals("Reported Title", placeholder?.title)
        assertNull(placeholder?.albumArtUriString)

        // Index warmed up: the next session-state emission re-resolves the SAME key.
        result = dbSong
        val resolved = source.resolveSong(request, localSong = null)

        assertEquals(dbSong, resolved)
        assertEquals(2, calls, "a miss must retry on the next emission, not be cached")
    }

    @Test
    fun `a resolved song is cached - repeat emissions hit the resolver exactly once`() = runTest {
        val lookup = CountingLookup(dbSong)
        val source = SessionDisplaySource { lookup.lookup(it) }
        val request = source.requestFor(remoteStint(P2pPlaybackStatus.PLAYING))

        val first = source.resolveSong(request, localSong = null)
        val second = source.resolveSong(request, localSong = null)
        val third = source.resolveSong(request, localSong = null)

        assertEquals(dbSong, first)
        assertEquals(first, second)
        assertEquals(second, third)
        assertEquals(1, lookup.calls)
    }

    @Test
    fun `the placeholder is never stored in the cache`() = runTest {
        var result: Song? = null
        val source = SessionDisplaySource { result }
        val request = source.requestFor(remoteStint(P2pPlaybackStatus.PLAYING))

        val placeholder = source.resolveSong(request, localSong = null)
        assertNotEquals(dbSong, placeholder)
        assertNull(placeholder?.albumArtUriString)

        // Prove nothing was cached under the miss: the resolver is still consulted.
        result = dbSong
        assertEquals(dbSong, source.resolveSong(request, localSong = null))
    }

    // ------------------------------------------------ session mode nullability rules

    @Test
    fun `session modes are null for local, takeover and unknown sources`() {
        val source = SessionDisplaySource { null }

        assertNull(source.shuffleMode(null))
        assertNull(source.repeatMode(null))
        assertNull(source.shuffleMode(SessionSource.Local(remoteStint(P2pPlaybackStatus.PLAYING).stint)))
        assertNull(source.repeatMode(SessionSource.Local(remoteStint(P2pPlaybackStatus.PLAYING).stint)))
        assertNull(
            source.shuffleMode(
                SessionSource.TakeoverInFlight("peer-a", "Phone", "hash-1", 0.0),
            ),
        )
    }

    @Test
    fun `session modes follow the remote stint when reported`() {
        val source = SessionDisplaySource { null }
        val stint = remoteStint(P2pPlaybackStatus.PLAYING).stint.copy(shuffle = true, repeat = P2pRepeatMode.ALL)

        assertEquals(true, source.shuffleMode(remoteStint(P2pPlaybackStatus.PLAYING).copy(stint = stint)))
        assertEquals(P2pRepeatMode.ALL, source.repeatMode(remoteStint(P2pPlaybackStatus.PLAYING).copy(stint = stint)))
    }

    @Test
    fun `session modes are null when the peer did not report them`() {
        val source = SessionDisplaySource { null }

        assertNull(source.shuffleMode(remoteStint(P2pPlaybackStatus.PLAYING)))
        assertNull(source.repeatMode(remoteStint(P2pPlaybackStatus.PLAYING)))
    }
}
