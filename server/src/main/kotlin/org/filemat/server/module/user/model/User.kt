package org.filemat.server.module.user.model

import com.github.f4b6a3.ulid.Ulid
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

@Table("users")
data class User(
    @Column("user_id")
    val userId: Ulid,
    @Column("email")
    val email: String,
    @Column("username")
    val username: String,
    @Column("password")
    val password: String,
    @Column("mfa_totp_secret")
    val mfaTotpSecret: String?,
    @Column("mfa_totp_status")
    val mfaTotpStatus: Boolean,
    @Column("mfa_totp_codes")
    val mfaTotpCodes: List<String>?,
    @Column("created_date")
    val createdDate: Long,
    @Column("last_login_date")
    val lastLoginDate: Long?,
    @Column("is_banned")
    val isBanned: Boolean,
)