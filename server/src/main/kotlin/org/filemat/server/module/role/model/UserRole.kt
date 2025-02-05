package org.filemat.server.module.role.model

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.module.permission.model.Permission
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

@Table("user_roles")
data class UserRole(
    @Column("role_id")
    val roleId: Ulid,
    @Column("user_id")
    val userId: Ulid,
    @Column("created_date")
    val createdDate: Long,
)