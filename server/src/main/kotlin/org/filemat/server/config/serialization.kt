package org.filemat.server.config

import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder


object UlidSerializer : KSerializer<Ulid> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Ulid", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Ulid) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Ulid {
        return Ulid.from(decoder.decodeString())
    }
}