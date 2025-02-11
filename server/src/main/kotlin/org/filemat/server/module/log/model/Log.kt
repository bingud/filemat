package org.filemat.server.module.log.model

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.module.user.model.UserAction
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

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
    val initiatorId: Ulid?,
    @Column("initiator_ip")
    val initiatorIp: String?,
    @Column("target_id")
    val targetId: Ulid?,
    @Column("metadata")
    val metadata: Map<String, String>,
)