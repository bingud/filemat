package org.filemat.server.module.auth.model

import com.github.f4b6a3.ulid.Ulid
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

@Table("auth_token")
data class AuthToken(
    @Column("auth_token")
    val authToken: String,
    @Column("user_id")
    val userId: Ulid,
    @Column("created_date")
    val createdDate: Long,
    @Column("user_agent")
    val userAgent: String,
    @Column("max_age")
    val maxAge: Long,
)