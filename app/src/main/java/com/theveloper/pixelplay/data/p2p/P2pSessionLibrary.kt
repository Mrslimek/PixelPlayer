package com.theveloper.pixelplay.data.p2p

import com.theveloper.pixelplay.data.model.Song

/**
 * Local library seam for the shared-session extension (contract section 8).
 * Production implementation hashes the phone's own music files lazily, cached by
 * (path, size, mtime). Both methods must be safe to call off the main thread.
 */
interface P2pSessionLibrary {

    /**
     * SHA-256 of the audio file's bytes for [song], cached-first; hashing on demand is the
     * acceptable fallback (this path NEVER runs on the main thread and never blocks playback
     * start). Null when the file is unreadable — the device then simply omits the session
     * fields on state_update.
     */
    suspend fun trackHashFor(song: Song): String?

    /** Resolves a peer's [trackHash] against the local index; null = library_unsynced. */
    suspend fun songForHash(trackHash: String): Song?

    /** Warm the index in the background (enumerate + hash the local library, off-main). */
    fun startBackgroundIndexing()
}
