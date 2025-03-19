package org.filemat.server.module.role.model

import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.filemat.server.config.UlidSerializer
import org.filemat.server.module.permission.model.Permission
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

/**
 * Role object
 */
@Serializable
data class Role(
    @Serializable(with = UlidSerializer::class)
    override val roleId: Ulid,
    override val name: String,
    override val createdDate: Long,
    override val permissions: List<Permission>,
) : ARole()


/**
 * Role DTO for database
 */
@Table("role")
data class RoleDto(
    @Column("role_id")
    override val roleId: Ulid,
    @Column("name")
    override val name: String,
    @Column("created_date")
    override val createdDate: Long,
    @Column("permissions")
    val permissions: String,
) : ABaseRole() {
    fun toRole(): Role {
        return Role(
            roleId = roleId, name = name, createdDate = createdDate, permissions = Json.decodeFromString<List<Int>>(permissions).map { Permission.fromInt(it) }
        )
    }
}

/**
 * Role with metadata
 */
@Serializable
data class RoleMeta(
    @Serializable(with = UlidSerializer::class)
    override val roleId: Ulid,
    override val name: String,
    override val createdDate: Long,
    override val permissions: List<Permission>,
    override val userIds: List<@Serializable(UlidSerializer::class) Ulid>,
) : ARoleMeta() {
    companion object {
        fun from(r: Role, userIds: List<Ulid>): RoleMeta {
            return RoleMeta(
                roleId = r.roleId,
                name = r.name,
                createdDate = r.createdDate,
                permissions = r.permissions,
                userIds = userIds
            )
        }
    }
}



abstract class ABaseRole {
    abstract val roleId: Ulid
    abstract val name: String
    abstract val createdDate: Long
}

abstract class ARole : ABaseRole() {
    abstract val permissions: List<Permission>
}

abstract class ARoleMeta : ARole() {
    abstract val userIds: List<Ulid>
}