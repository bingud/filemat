package org.filemat.server.module.user.model

import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.Serializable
import org.filemat.server.config.UlidSerializer
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table


@Serializable
@Table("users")
data class MiniUser(
    @Serializable(UlidSerializer::class)
    @Column("user_id")
    val userId: Ulid,
    @Column("username")
    val username: String
)