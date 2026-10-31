package com.theveloper.pixelplay.data.p2p

import android.content.Context
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.database.toSong
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Local-library hash index for the shared-session extension (contract section 8).
 * Track identity = lowercase hex SHA-256 of the audio FILE's bytes (tags included —
 * externally synced libraries are byte-identical). Hashes are cached by (path, size, mtime)
 * so re-hashes only happen when a file actually changed; a background pass warms the index
 * off the main thread, and hash-on-demand covers anything not yet warmed (never on main,
 * never blocking playback start — only the state broadcast awaits it).
 */
@Singleton
class MusicLibraryHashIndex @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicDao: MusicDao,
    @AppScope private val appScope: CoroutineScope,
) : P2pSessionLibrary {

    private data class CacheKey(val path: String, val size: Long, val mtimeMs: Long)

    private val hashCache = ConcurrentHashMap<CacheKey, String>()
    private val songByHash = ConcurrentHashMap<String, Song>()
    @Volatile private var indexing = false

    override fun startBackgroundIndexing() {
        synchronized(this) {
            if (indexing) return
            indexing = true
        }
        appScope.launch(Dispatchers.IO) {
            try {
                val songs = musicDao.getAllSongs().first().map { it.toSong() }
                for (song in songs) {
                    if (song.path.isBlank()) continue
                    // Sequential, off-main, cached: warm the index without hammering I/O.
                    trackHashFor(song)
                }
                Timber.tag("P2P").i("library hash index warmed (%d songs)", songs.size)
            } catch (t: Throwable) {
                Timber.tag("P2P").w(t, "library hash indexing failed")
            } finally {
                indexing = false
            }
        }
    }

    override suspend fun trackHashFor(song: Song): String? = withContext(Dispatchers.IO) {
        val file = File(song.path)
        val key = CacheKey(song.path, runCatching { file.length() }.getOrDefault(0L), runCatching { file.lastModified() }.getOrDefault(0L))
        hashCache[key]?.let { return@withContext it }
        val hash = hashFile(song) ?: return@withContext null
        hashCache[key] = hash
        songByHash[hash] = song
        hash
    }

    override suspend fun songForHash(trackHash: String): Song? = withContext(Dispatchers.IO) {
        songByHash[trackHash]
    }

    /** SHA-256 over the file's bytes via the content resolver (permission-safe path). */
    private fun hashFile(song: Song): String? = runCatching {
        val uri = android.net.Uri.parse(song.contentUriString)
        context.contentResolver.openInputStream(uri)?.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }.getOrNull()?.also {
        Timber.tag("P2P").d("hashed %s -> %s…", song.title, it.take(8))
    }
}
