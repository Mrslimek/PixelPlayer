package com.theveloper.pixelplay.data.p2p

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Embedded Ktor CIO WebSocket server listening on a dynamic port, accepting inbound
 * peer connections (callee side). Follows the MediaFileHttpServerService dynamic-port idiom.
 */
class PeerWsServer(
    private val scope: CoroutineScope,
    private val onSocketAccepted: (PeerSocket) -> Unit,
    private val onSocketClosed: (PeerSocket, code: Int, reason: String) -> Unit,
    private val onText: (PeerSocket, text: String) -> Unit,
) {

    @Volatile
    var port: Int = 0
        private set

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val connectionCounter = AtomicLong(0)

    /** Starts listening. Returns the bound port or null on failure. */
    fun start(): Int? {
        check(server == null) { "PeerWsServer already started" }
        repeat(START_RETRIES) { attempt ->
            val candidate = pickFreePort()
            try {
                val embedded = embeddedServer(CIO, port = candidate, host = "0.0.0.0") {
                    installWebSocketsAndRoutes()
                }
                embedded.start(wait = false)
                server = embedded
                port = candidate
                Timber.tag("P2P").i("WS server listening on port %d (attempt %d)", candidate, attempt + 1)
                return candidate
            } catch (e: Exception) {
                Timber.tag("P2P").w(e, "WS server bind failed on port %d (attempt %d)", candidate, attempt + 1)
                server = null
            }
        }
        return null
    }

    fun stop() {
        val s = server ?: return
        server = null
        runCatching { s.stop(100, 500) }
        Timber.tag("P2P").i("WS server stopped (port %d)", port)
        port = 0
    }

    private fun Application.installWebSocketsAndRoutes() {
        install(WebSockets)
        routing {
            webSocket("/") {
                val socket = KtorServerSocket(
                    debugName = "inbound#${connectionCounter.incrementAndGet()}",
                    session = this,
                    handlerJob = currentCoroutineContext().job,
                )
                val senderCompleted = CompletableDeferred<Unit>()
                socket.startSender(senderCompleted)
                onSocketAccepted(socket)
                try {
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> onText(socket, frame.readText())
                            is Frame.Close -> break
                            else -> Unit
                        }
                    }
                } finally {
                    socket.closeOutbound()
                    senderCompleted.await()
                    val closeInfo = runCatching {
                        withTimeoutOrNull(1_000) { closeReason.await() }
                    }.getOrNull()
                    onSocketClosed(
                        socket,
                        closeInfo?.code?.toInt() ?: P2pProtocolSpec.CLOSE_INTERNAL_ERROR,
                        closeInfo?.message ?: "abnormal closure",
                    )
                }
            }
        }
    }

    private inner class KtorServerSocket(
        override val debugName: String,
        private val session: io.ktor.server.websocket.WebSocketServerSession,
        private val handlerJob: Job,
    ) : PeerSocket {

        private val outbound = Channel<Outbound>(Channel.BUFFERED)
        @Volatile private var outboundClosed = false

        fun startSender(done: CompletableDeferred<Unit>) {
            scope.launch {
                try {
                    for (message in outbound) {
                        when (message) {
                            is Outbound.Text -> runCatching { session.send(Frame.Text(message.text)) }
                                .onFailure { Timber.tag("P2P").d("send failed on %s: %s", debugName, it.message) }
                            is Outbound.Close -> {
                                runCatching { session.close(CloseReason(message.code.toShort(), message.reason)) }
                                break
                            }
                        }
                    }
                } finally {
                    done.complete(Unit)
                }
            }
        }

        fun closeOutbound() {
            outboundClosed = true
            outbound.close()
        }

        override fun send(text: String): Boolean {
            if (outboundClosed) return false
            return try {
                outbound.trySend(Outbound.Text(text)).isSuccess
            } catch (e: Exception) {
                false
            }
        }

        override fun close(code: Int, reason: String) {
            if (outboundClosed) return
            runCatching { outbound.trySend(Outbound.Close(code, reason)) }
                .onFailure { outboundClosed = true }
        }

        override fun abort() {
            // Cancel the session coroutine: CIO aborts the TCP link without a close handshake.
            runCatching { handlerJob.cancel() }
        }

        override fun remoteAddress(): String {
            val origin = runCatching { session.call.request.origin }.getOrNull()
            val host = origin?.remoteHost ?: "unknown"
            val remotePort = origin?.remotePort ?: 0
            return "$host:$remotePort"
        }
    }

    private companion object {
        const val START_RETRIES = 3

        fun pickFreePort(): Int = runCatching {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("0.0.0.0", 0))
                socket.localPort
            }
        }.getOrDefault(0)
    }
}

/** Outbound queue items for the server-side socket adapter. */
private sealed interface Outbound {
    data class Text(val text: String) : Outbound
    data class Close(val code: Int, val reason: String) : Outbound
}
