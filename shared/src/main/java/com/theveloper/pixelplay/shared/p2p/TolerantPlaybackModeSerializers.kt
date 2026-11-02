package com.theveloper.pixelplay.shared.p2p

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Playback-mode fields of `state_update` (contract section 6, added 2026-11-02) must
 * never fail a frame: absent / JSON-null / type-mismatched / unparseable values decode
 * to null and are dropped, FlexibleLongSerializer-style tolerance.
 */
object TolerantBooleanSerializer : KSerializer<Boolean?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("TolerantBoolean", PrimitiveKind.BOOLEAN).nullable

    override fun deserialize(decoder: Decoder): Boolean? {
        if (decoder !is JsonDecoder) return null
        return when (val element = decoder.decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> element.booleanOrNull
                ?: when (element.content.trim().lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
            else -> null
        }
    }

    override fun serialize(encoder: Encoder, value: Boolean?) {
        if (value == null) encoder.encodeNull() else encoder.encodeBoolean(value)
    }
}

/** Any JSON primitive decodes to its string content; anything else (or null) decodes to null. */
object TolerantStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("TolerantString", PrimitiveKind.STRING).nullable

    override fun deserialize(decoder: Decoder): String? {
        if (decoder !is JsonDecoder) return null
        return when (val element = decoder.decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> element.content
            else -> null
        }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}
