package org.filemat.server.config

import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.listSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json


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


object UlidListSerializer : KSerializer<List<Ulid>> {
    private val delegate = ListSerializer(UlidSerializer)

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<Ulid>) {
        delegate.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): List<Ulid> {
        return delegate.deserialize(decoder)
    }
}
