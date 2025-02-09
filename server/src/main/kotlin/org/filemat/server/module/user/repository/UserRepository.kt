package org.filemat.server.module.user.repository

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.module.user.model.User
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface UserRepository : CrudRepository<User, Ulid> {

    @Modifying
    @Query("INSERT INTO users " +
            "(user_id, email, username, password, mfa_totp_secret, mfa_totp_status, mfa_totp_codes, created_date, last_login_date, is_banned) " +
            "VALUES (:userId, :email, :username, :password, :mfaTotpSecret, :mfaTotpStatus, :mfaTotpCodes, :createdDate, :lastLoginDate, :isBanned)")
    fun createUser(
        userId: String,
        email: String,
        username: String,
        password: String,
        mfaTotpSecret: String?,
        mfaTotpStatus: Boolean,
        mfaTotpCodes: String?,
        createdDate: Long,
        lastLoginDate: Long?,
        isBanned: Int,
    )

}