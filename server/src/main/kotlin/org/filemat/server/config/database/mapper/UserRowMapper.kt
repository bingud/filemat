package org.filemat.server.config.database.mapper

import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.json.Json
import org.filemat.server.common.util.toBoolean
import org.filemat.server.module.user.model.User
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.sql.ResultSet

@Component
class UserRowMapper : RowMapper<User> {
    override fun mapRow(rs: ResultSet, rowNum: Int): User {
        return User(
            userId = Ulid.from(rs.getString("user_id")),
            email = rs.getString("email"),
            username = rs.getString("username"),
            password = rs.getString("password"),
            mfaTotpSecret = rs.getString("mfa_totp_secret"),
            mfaTotpStatus = rs.getShort("mfa_totp_status").toBoolean(),
            mfaTotpCodes = rs.getString("mfa_totp_codes")?.let { Json.decodeFromString<List<String>>(it) },
            createdDate = rs.getLong("created_date"),
            lastLoginDate = rs.getLong("created_date"),
            isBanned = rs.getShort("is_banned").toBoolean(),
        )
    }
}