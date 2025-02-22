package org.filemat.server.config

import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
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
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Ulid", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: List<Ulid>) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): List<Ulid> {
        val rawList = decoder.decodeString()
        val list = Json.decodeFromString<List<String>>(rawList)
            .map { Ulid.from(it) }

        return list
    }
}

