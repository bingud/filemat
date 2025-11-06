package org.filemat.server.module.log.model

import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import org.filemat.server.config.UlidSerializer
import org.filemat.server.module.user.model.UserAction
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

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
    @Transient
    @Column("metadata")
    val metadataString: String? = null,
) {
    val metadata by lazy {
        if (metadataString == null) return@lazy null
        Json.decodeFromString<Map<String, String>>(metadataString)
    }
}