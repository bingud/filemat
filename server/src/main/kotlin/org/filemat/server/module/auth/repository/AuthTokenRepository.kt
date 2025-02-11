package org.filemat.server.module.auth.repository

import org.filemat.server.module.auth.model.AuthToken
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface AuthTokenRepository : CrudRepository<AuthToken, String> {

    @Modifying
    @Query("INSERT INTO auth_token (auth_token, user_id, created_date, user_agent, max_age) VALUES (:token, :userId, :date, :ua, :maxAge)")
    fun insertToken(token: String, userId: String, date: Long, ua: String, maxAge: Long)

}