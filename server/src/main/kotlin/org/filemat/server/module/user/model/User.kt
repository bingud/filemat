package org.filemat.server.module.user.model

import com.github.f4b6a3.ulid.Ulid
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

@Table("users")
data class User(
    @Id
    @Column("user_id")
    override val userId: Ulid,
    @Column("email")
    override val email: String,
    @Column("username")
    override val username: String,
    @Column("password")
    override val password: String,
    @Column("mfa_totp_secret")
    override val mfaTotpSecret: String?,
    @Column("mfa_totp_status")
    override val mfaTotpStatus: Boolean,
    @Column("mfa_totp_codes")
    override val mfaTotpCodes: List<String>?,
    @Column("mfa_totp_required")
    override val mfaTotpRequired: Boolean,
    @Column("created_date")
    override val createdDate: Long,
    @Column("last_login_date")
    override val lastLoginDate: Long?,
    @Column("is_banned")
    override val isBanned: Boolean,
    @Column("home_folder_path")
    override val homeFolderPath: String?
) : AUser()



