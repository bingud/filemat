package org.filemat.server.module.user.model

import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.Serializable
import org.filemat.server.config.UlidSerializer
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

@Serializable
@Table("users")
data class PublicUser(
    @Id
    @Column("user_id")
    @Serializable(UlidSerializer::class)
    override val userId: Ulid,
    @Column("email")
    override val email: String,
    @Column("username")
    override val username: String,
    @Column("mfa_totp_status")
    override val mfaTotpStatus: Boolean,
    @Column("mfa_totp_required")
    override val mfaTotpRequired: Boolean,
    @Column("created_date")
    override val createdDate: Long,
    @Column("last_login_date")
    override val lastLoginDate: Long?,
    @Column("is_banned")
    override val isBanned: Boolean,
) : APublicUser()
