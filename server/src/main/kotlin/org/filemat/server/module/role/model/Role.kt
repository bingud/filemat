package org.filemat.server.module.role.model

import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.json.Json
import org.filemat.server.module.permission.model.Permission
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table


data class Role(
    val roleId: Ulid,
    val name: String,
    val createdDate: Long,
    val permissions: List<Permission>,
)


@Table("role")
data class RoleModel(
    @Column("role_id")
    val roleId: Ulid,
    @Column("name")
    val name: String,
    @Column("created_date")
    val createdDate: Long,
    @Column("permissions")
    val permissions: String,
) {
    fun toRole(): Role {
        return Role(
            roleId = roleId, name = name, createdDate = createdDate, permissions = Json.decodeFromString<List<Int>>(permissions).map { Permission.fromInt(it) }
        )
    }
}