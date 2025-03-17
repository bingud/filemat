package org.filemat.server.module.user.model

import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.Serializable
import org.filemat.server.config.UlidSerializer
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

@Serializable
@Table("users")
data class PublicUser(
    @Column("user_id")
    @Serializable(UlidSerializer::class)
    val userId: Ulid,
    @Column("email")
    val email: String,
    @Column("username")
    val username: String,
    @Column("mfa_totp_status")
    val mfaTotpStatus: Boolean,
    @Column("created_date")
    val createdDate: Long,
    @Column("last_login_date")
    val lastLoginDate: Long?,
    @Column("is_banned")
    val isBanned: Boolean,
)