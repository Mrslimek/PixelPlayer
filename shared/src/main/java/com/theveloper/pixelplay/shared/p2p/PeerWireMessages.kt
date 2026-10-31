@file:UseSerializers(FlexibleLongSerializer::class)

package com.theveloper.pixelplay.shared.p2p

import kotlinx.serialization.SerialName
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

const val P2pProtocolVersion: Int = 1

/**
 * Wire DTOs for the Music Share peer protocol v1 (docs/PROTOCOL.md section 6).
 * One JSON text frame per message; type-specific fields are flattened into the
 * common envelope {v, type, id, from, ts, replyTo?}. The `type` discriminator
 * selects the concrete frame; unknown discriminators are reported by the codec
 * as UNKNOWN_TYPE so receivers can log `message_unknown` without disconnecting.
 */
@Serializable
@JsonClassDiscriminator("type")
sealed interface PeerWireMessage {
    val v: Int
    val id: String
    val from: String
    val ts: Long
    val replyTo: String?
}

@Serializable
@SerialName("hello")
data class P2pHello(
    override val v: Int = P2pProtocolVersion,
    override val id: String,
    override val from: String,
    override val ts: Long,
    override val replyTo: String? = null,
    val name: String = "",
    val platform: String = "",
    val appVersion: String = "",
) : PeerWireMessage

@Serializable
@SerialName("hello_ack")
data class P2pHelloAck(
    override val v: Int = P2pProtocolVersion,
    override val id: String,
    override val from: String,
    override val ts: Long,
    override val replyTo: String? = null,
    val name: String = "",
    val platform: String = "",
    val appVersion: String = "",
) : PeerWireMessage

@Serializable
@SerialName("ping")
data class P2pPing(
    override val v: Int = P2pProtocolVersion,
    override val id: String,
    override val from: String,
    override val ts: Long,
    override val replyTo: String? = null,
) : PeerWireMessage

@Serializable
@SerialName("pong")
data class P2pPong(
    override val v: Int = P2pProtocolVersion,
    override val id: String,
    override val from: String,
    override val ts: Long,
    override val replyTo: String? = null,
) : PeerWireMessage

@Serializable
@SerialName("state_update")
data class P2pStateUpdate(
    override val v: Int = P2pProtocolVersion,
    override val id: String,
    override val from: String,
    override val ts: Long,
    override val replyTo: String? = null,
    val track: String = "",
    val status: String = "",
    val positionSec: Double = 0.0,
    val seq: Long = 0L,
    val artist: String? = null,
    /** Optional shared-session field (contract section 8): lowercase hex SHA-256 of the
     *  audio file's bytes. Absent = the peer does not implement the session extension. */
    val trackHash: String? = null,
    /** Optional shared-session field: UUID v4 of the continuous playback stint — fresh on
     *  every track start and takeover; kept across pause->resume of the same stint. */
    val playbackId: String? = null,
) : PeerWireMessage

@Serializable
@SerialName("test")
data class P2pTest(
    override val v: Int = P2pProtocolVersion,
    override val id: String,
    override val from: String,
    override val ts: Long,
    override val replyTo: String? = null,
    val text: String = "",
) : PeerWireMessage

@Serializable
@SerialName("command")
data class P2pCommand(
    override val v: Int = P2pProtocolVersion,
    override val id: String,
    override val from: String,
    override val ts: Long,
    override val replyTo: String? = null,
    /** Wire action token; mapped via [P2pCommandAction.fromWire] — unknown tokens are
     *  command_ignored (reason=unsupported) by the receiver, never a disconnect. */
    val action: String = "",
    /** Seconds; REQUIRED for action="seek", ignored otherwise. */
    val positionSec: Double? = null,
) : PeerWireMessage

@Serializable
@SerialName("goodbye")
data class P2pGoodbye(
    override val v: Int = P2pProtocolVersion,
    override val id: String,
    override val from: String,
    override val ts: Long,
    override val replyTo: String? = null,
    val reason: String = "",
) : PeerWireMessage

/** Remote playback-control actions (contract section 6, optional v1 extension). */
enum class P2pCommandAction(val wireValue: String) {
    PLAY("play"),
    PAUSE("pause"),
    TOGGLE("toggle"),
    STOP("stop"),
    SEEK("seek"),
    NEXT("next"),
    PREVIOUS("previous");

    val needsPosition: Boolean get() = this == SEEK

    companion object {
        fun fromWire(value: String): P2pCommandAction? =
            entries.firstOrNull { it.wireValue == value }
    }
}

enum class P2pPlaybackStatus(val wireValue: String) {
    PLAYING("playing"),
    PAUSED("paused"),
    STOPPED("stopped");

    companion object {
        fun fromWire(value: String): P2pPlaybackStatus? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/** Type-specific payload of a `state_update` frame (kept also as a standalone value object). */
@Serializable
data class P2pPeerPlaybackState(
    val track: String = "",
    val artist: String? = null,
    val status: String = P2pPlaybackStatus.STOPPED.wireValue,
    val positionSec: Double = 0.0,
    val seq: Long = 0L,
    /** Optional shared-session fields (contract section 8). */
    val trackHash: String? = null,
    val playbackId: String? = null,
)
