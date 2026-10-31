package com.theveloper.pixelplay.shared.p2p

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Envelope + payload codec. Tolerant on decode (ignoreUnknownKeys / lenient) so
 * future peers stay forward compatible; the receiver rules of docs/PROTOCOL.md
 * section 6 (malformed / duplicate / unknown) are decided by the caller.
 */
object PeerWireCodec {

    enum class DecodeFailure { MALFORMED, UNKNOWN_TYPE }

    private val knownTypes = setOf(
        "hello", "hello_ack", "ping", "pong", "state_update", "test", "goodbye", "command",
    )

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    fun encode(message: PeerWireMessage): String =
        json.encodeToString(PeerWireMessage.serializer(), message)

    /** Returns [DecodeFailure.MALFORMED] when the frame is not a valid envelope, or
     *  [DecodeFailure.UNKNOWN_TYPE] when the `type` discriminator names no known message. */
    fun decode(text: String): Pair<PeerWireMessage?, DecodeFailure?> {
        val root = parseJsonObject(text)
            ?: return null to DecodeFailure.MALFORMED
        val type = (root["type"] as? JsonPrimitive)?.content
        if (type == null || type !in knownTypes) return null to DecodeFailure.UNKNOWN_TYPE
        val message = try {
            json.decodeFromString(PeerWireMessage.serializer(), text)
        } catch (_: Exception) {
            return null to DecodeFailure.MALFORMED
        }
        return message to null
    }

    /** Envelope required-field check per docs/PROTOCOL.md section 6. */
    fun envelopeViolation(message: PeerWireMessage): String? {
        if (message.id.isEmpty()) return "id"
        if (message.from.isEmpty()) return "from"
        if (message.ts <= 0L) return "ts"
        return null
    }

    fun parseJsonObject(text: String): JsonObject? = try {
        json.parseToJsonElement(text) as? JsonObject
    } catch (_: Exception) {
        null
    }
}
