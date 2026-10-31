package com.theveloper.pixelplay.data.p2p

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber

/**
 * OkHttp WebSocket dialer for outbound peer connections.
 *
 * Carries the reference MVP's defect-5 guard: a dial whose HTTP upgrade never completes
 * (frozen peer accepting TCP into its backlog) is abandoned after [dialTimeoutMs] so dead
 * dials fail at ~5 s and escalate the reconnect ladder instead of parking forever.
 */
class PeerWsClient(
    private val httpClient: OkHttpClient,
    private val dialTimeoutMs: Long = P2pProtocolSpec.DIAL_TIMEOUT_MS,
) {

    interface Listener {
        /** Socket open (HTTP upgrade complete); the caller must send `hello` now. */
        fun onOpen(gen: Int, socket: PeerSocket)

        fun onText(gen: Int, socket: PeerSocket, text: String)

        fun onClosed(gen: Int, socket: PeerSocket, code: Int, reason: String, remote: Boolean)

        /** Dial-level failure (TCP refused, DNS, dial timeout) — no socket was ever opened. */
        fun onDialFailed(gen: Int, detail: String)

        /** Live-socket failure after open (transport error or local abort). */
        fun onSocketFailed(gen: Int, socket: PeerSocket, detail: String)
    }

    private val timeoutExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "p2p-dial-timeout").apply { isDaemon = true }
    }

    fun dial(host: String, port: Int, gen: Int, listener: Listener): DialTicket {
        val request = Request.Builder().url("ws://$host:$port/").build()
        val delegate = DialingSocket(gen, "$host:$port", listener)
        val webSocket = httpClient.newWebSocket(request, delegate)
        val ticket = DialTicket(webSocket, delegate)
        delegate.armDialTimeout()
        return ticket
    }

    class DialTicket internal constructor(
        private val webSocket: WebSocket,
        private val delegate: DialingSocket,
    ) {
        /** Abandons a dial that never upgraded (also cancels the pending timeout). */
        fun cancel() {
            delegate.disarmDialTimeout()
            runCatching { webSocket.cancel() }
        }
    }

    internal inner class DialingSocket(
        private val gen: Int,
        private val remote: String,
        private val listener: Listener,
    ) : WebSocketListener() {

        private val opened = AtomicBoolean(false)
        private val timedOut = AtomicBoolean(false)
        @Volatile private var socket: PeerSocket? = null
        @Volatile private var live: WebSocket? = null
        @Volatile private var abortReason: String? = null
        @Volatile private var timeoutArmed = true

        fun armDialTimeout() {
            timeoutExecutor.schedule({
                if (!timeoutArmed || opened.get()) return@schedule
                if (timedOut.compareAndSet(false, true)) {
                    runCatching { live?.cancel() }
                    listener.onDialFailed(gen, "dial timeout: no HTTP upgrade within ${dialTimeoutMs}ms")
                }
            }, dialTimeoutMs, TimeUnit.MILLISECONDS)
        }

        fun disarmDialTimeout() {
            timeoutArmed = false
        }

        /** Local hard abort (keepalive teardown): remember why, then cancel the TCP link. */
        fun abortLocal(reason: String) {
            abortReason = reason
            runCatching { live?.cancel() }
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (timedOut.get()) return
            timeoutArmed = false
            opened.set(true)
            live = webSocket
            val s = OkHttpPeerSocket(this, webSocket, "dial#$gen ws://$remote")
            socket = s
            listener.onOpen(gen, s)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val s = socket ?: return
            listener.onText(gen, s, text)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            val s = socket ?: return
            listener.onClosed(gen, s, code, reason, remote = true)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val detail = abortReason?.let { "local abort ($it)" } ?: (t.message ?: t.javaClass.simpleName)
            if (!opened.get()) {
                if (timedOut.get()) return
                listener.onDialFailed(gen, detail)
                return
            }
            val s = socket ?: return
            listener.onSocketFailed(gen, s, detail)
        }
    }

    private class OkHttpPeerSocket(
        private val delegate: DialingSocket,
        private val webSocket: WebSocket,
        override val debugName: String,
    ) : PeerSocket {

        override fun send(text: String): Boolean = try {
            webSocket.send(text)
        } catch (e: Exception) {
            Timber.tag("P2P").d(e, "send failed on %s", debugName)
            false
        }

        override fun close(code: Int, reason: String) {
            runCatching { webSocket.close(code, reason) }
        }

        override fun abort() {
            delegate.abortLocal("hard close")
        }

        override fun remoteAddress(): String = debugName
    }
}
