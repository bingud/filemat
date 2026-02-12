package org.filemat.server.module.user.repository

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.module.user.model.User
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository


/**
 * TODO
 * change deps to user repository class
 */
@Repository
interface UserRepository : CrudRepository<User, Ulid> {

    @Query("SELECT CASE WHEN email = :email THEN 'email' WHEN username = :username THEN 'username' END AS conflict FROM users WHERE email = :email OR username = :username LIMIT 1")
    fun exists(email: String, username: String): String?

    @Modifying
    @Query("INSERT INTO users " +
            "(user_id, email, username, password, mfa_totp_secret, mfa_totp_status, mfa_totp_codes, mfa_totp_required, created_date, last_login_date, is_banned) " +
            "VALUES (:userId, :email, :username, :password, :mfaTotpSecret, :mfaTotpStatus, :mfaTotpCodes, :mfaTotpRequired, :createdDate, :lastLoginDate, :isBanned)")
    fun createUser(
        userId: Ulid,
        email: String,
        username: String,
        password: String,
        mfaTotpSecret: String?,
        mfaTotpStatus: Boolean,
        mfaTotpCodes: String?,
        mfaTotpRequired: Boolean,
        createdDate: Long,
        lastLoginDate: Long?,
        isBanned: Boolean,
    )

    @Query("SELECT * FROM users WHERE username = :username")
    fun getByUsername(username: String): User?

    @Query("SELECT * FROM users WHERE email = :email")
    fun getByEmail(email: String): User?

    @Query("SELECT * FROM users WHERE user_id = :userId")
    fun getByUserId(userId: Ulid): User?

    @Modifying
    @Query("UPDATE users SET last_login_date = :newDate WHERE user_id = :userId")
    fun updateLastLoginDate(userId: Ulid, newDate: Long): Int

    @Modifying
    @Query("UPDATE users SET mfa_totp_status = :status, mfa_totp_secret = :secret, mfa_totp_codes = :codes, mfa_totp_required = :isRequired WHERE user_id = :userId")
    fun updateTotpMfa(userId: Ulid, status: Boolean, secret: String?, codes: String?, isRequired: Boolean): Int

    @Modifying
    @Query("UPDATE users SET password = :password WHERE user_id = :userId")
    fun updatePassword(userId: Ulid, password: String): Int

    @Modifying
    @Query("UPDATE users SET username = :username WHERE user_id = :userId")
    fun updateUsername(userId: Ulid, username: String): Int

    @Modifying
    @Query("UPDATE users SET email = :email WHERE user_id = :userId")
    fun updateEmail(userId: Ulid, email: String): Int

    @Modifying
    @Query("UPDATE users SET home_folder_path = :homeFolderPath WHERE user_id = :userId")
    fun updateHomeFolderPath(homeFolderPath: String, userId: Ulid): Int
}
