package com.streamify.supabase.v5

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Serializes 64-bit [Long] values as base-10 strings in JSON to prevent
 * IEEE-754 53-bit precision loss on WasmJS / JavaScript runtimes.
 */
object LongAsStringSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.streamify.supabase.v5.LongAsString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Long {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element is JsonPrimitive) {
                element.contentOrNull?.toLongOrNull()?.let { return it }
                element.longOrNull?.let { return it }
            }
        }
        return decoder.decodeString().toLong()
    }
}
