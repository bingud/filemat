package org.filemat.server.module.auth.repository

import org.filemat.server.module.auth.model.AuthToken
import org.filemat.server.module.user.model.User
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface AuthTokenRepository : CrudRepository<AuthToken, String> {

    @Modifying
    @Query("INSERT INTO auth_token (auth_token, user_id, created_date, user_agent, max_age) VALUES (:token, :userId, :date, :ua, :maxAge)")
    fun insertToken(token: String, userId: String, date: Long, ua: String, maxAge: Long)

    @Modifying
    @Query("DELETE FROM auth_token WHERE auth_token = :token")
    fun deleteToken(token: String): Int

    @Query("SELECT users.* FROM users JOIN auth_token ON users.user_id = auth_token.user_id WHERE auth_token.auth_token = :token AND (:unixNow < auth_token.created_date + auth_token.max_age)")
    fun getUserByToken(token: String, unixNow: Long): User?

    @Query("SELECT * FROM auth_token WHERE auth_token = :token AND (:unixNow < created_date + max_age)")
    fun getToken(token: String, unixNow: Long): AuthToken?

    @Modifying
    @Query("DELETE FROM auth_token WHERE (:unixNow > created_date + max_age)")
    fun clearExpiredTokens(unixNow: Long): Int
}