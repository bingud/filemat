package org.filemat.server.module.log.model

import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.filemat.server.common.util.json
import org.filemat.server.config.UlidSerializer
import org.filemat.server.module.user.model.UserAction
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

// Catastrophic code
// because kotlin serialization is bad
// and JDBC is bad
// cant wait to ditch everything and use libraries from 21st century

@Serializable
@Table("log")
data class Log(
    @Column("log_id")
    val logId: Int,
    @Column("level")
    val level: LogLevel,
    @Column("type")
    val type: LogType,
    @Column("action")
    val action: UserAction,
    @Column("created_date")
    val createdDate: Long,
    @Column("description")
    val description: String,
    @Column("message")
    val message: String,
    @Column("initiator_user_id")
    @Serializable(with = UlidSerializer::class)
    val initiatorId: Ulid?,
    @Column("initiator_ip")
    val initiatorIp: String?,
    @Column("target_id")
    @Serializable(with = UlidSerializer::class)
    val targetId: Ulid?,
    @Column("metadata")
    @SerialName("meta")
    val metadataString: String? = null,
) {
    private val metadataJson  by lazy {
        metadataString ?: return@lazy null
        Json.parseToJsonElement(metadataString)
    }

    val metadata by lazy {
        metadataJson ?: return@lazy null
        Json.decodeFromJsonElement<Map<String, String>>(metadataJson!!)
    }

    fun serialize(): String {
        // Kotlin serialization object is arbitrarily immutable
        val json = Json.encodeToJsonElement(this)

        return json {
            json.jsonObject.entries.forEach { entry ->
                put(entry.key, entry.value)
            }

            put("metadata", metadataJson)
        }
    }

}


fun List<Log>.serializeList(): String {
    val s = StringBuilder()
    s.append("[")

    val size = this.size

    this.forEachIndexed { index, log ->
        s.append(log.serialize())
        if (index != size - 1) s.append(",")
    }

    s.append("]")
    return s.toString()
}