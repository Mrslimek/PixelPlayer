package com.theveloper.pixelplay.shared.p2p

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToLong

/**
 * Contract section 6 types `ts` as "number"; the reference mac peer emits fractional
 * epoch values (e.g. 1793446489411.0278) while Android emits integral longs. This
 * serializer accepts both on decode and always encodes as an integral long.
 */
object FlexibleLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long {
        val primitive = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive
            ?: return decoder.decodeLong()
        return primitive.longOrNull
            ?: primitive.doubleOrNull?.roundToLong()
            ?: primitive.content.trim().toLongOrNull()
            ?: primitive.content.trim().toDoubleOrNull()?.roundToLong()
            ?: 0L
    }

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeLong(value)
    }
}
