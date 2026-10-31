package com.theveloper.pixelplay.data.p2p

/** Transport-agnostic view of one live WebSocket to a peer. */
interface PeerSocket {
    /** Debug identity of the underlying connection. */
    val debugName: String

    /** Best-effort enqueue of one JSON text frame. False when the socket is dead. */
    fun send(text: String): Boolean

    /** Graceful close: send a close frame with the given code + reason. */
    fun close(code: Int, reason: String)

    /** Hard abort of the TCP link (no close-frame handshake). Used for keepalive teardown
     *  of frozen peers, where a graceful close would park until the peer answers. */
    fun abort()

    fun remoteAddress(): String
}
