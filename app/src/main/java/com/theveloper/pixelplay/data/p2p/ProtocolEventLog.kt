package com.theveloper.pixelplay.data.p2p

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * In-memory ring buffer of human-readable protocol events, surfaced by the
 * debug comm-log screen (Settings -> Developer -> P2P communication log).
 * Every entry is mirrored to logcat under the "P2P" Timber tag.
 */
@Singleton
class ProtocolEventLog @Inject constructor() {

    enum class Direction(val arrow: String) {
        IN("←"),
        OUT("→"),
        PAIR("⇄"),
        LOCAL("•"),
        WARN("⚠"),
        CLOSE("×"),
        ERROR("×"),
    }

    data class P2pEvent(
        val seqNo: Long,
        val tsMs: Long,
        val direction: Direction,
        val text: String,
        val detail: String = "",
    ) {
        val rendered: String get() = "${direction.arrow} $text"
    }

    private val buffer = ArrayDeque<P2pEvent>(CAPACITY)
    private val _events = MutableStateFlow<List<P2pEvent>>(emptyList())
    val events: StateFlow<List<P2pEvent>> = _events.asStateFlow()

    private var nextSeq = 1L

    fun log(direction: Direction, text: String, detail: String = "") {
        val event = synchronized(buffer) {
            val e = P2pEvent(nextSeq++, System.currentTimeMillis(), direction, text, detail)
            buffer.addLast(e)
            while (buffer.size > CAPACITY) buffer.removeFirst()
            e
        }
        when (direction) {
            Direction.ERROR -> Timber.tag(TAG).w(" %s %s", event.rendered, detail)
            Direction.WARN -> Timber.tag(TAG).w(" %s %s", event.rendered, detail)
            Direction.CLOSE -> Timber.tag(TAG).w(" %s %s", event.rendered, detail)
            Direction.LOCAL -> Timber.tag(TAG).i(" %s %s", event.rendered, detail)
            Direction.PAIR -> Timber.tag(TAG).i(" %s %s", event.rendered, detail)
            Direction.IN -> Timber.tag(TAG).i(" %s %s", event.rendered, detail)
            Direction.OUT -> Timber.tag(TAG).i(" %s %s", event.rendered, detail)
        }
        _events.value = synchronized(buffer) { buffer.toList() }
    }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
        _events.value = emptyList()
    }

    private companion object {
        const val TAG = "P2P"
        const val CAPACITY = 512
    }
}
