package org.filemat.server.module.permission.model

import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.Serializable
import org.filemat.server.config.UlidSerializer
import org.filemat.server.module.file.model.FilesystemEntityType
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

@Serializable
data class EntityPermission(
    @Serializable(UlidSerializer::class)
    val permissionId: Ulid,
    val permissionType: PermissionType,
    @Serializable(UlidSerializer::class)
    val entityId: Ulid,
    @Serializable(UlidSerializer::class)
    val userId: Ulid?,
    @Serializable(UlidSerializer::class)
    val roleId: Ulid?,
    val permissions: List<FilePermission>,
    val createdDate: Long,
) {
    init {
        if (userId == null && roleId == null || userId != null && roleId != null) throw IllegalStateException("Permission must have either a role or user ID.")
    }
}


@Table("permissions")
data class EntityPermissionDto(
    @Column("permission_id")
    val permissionId: Ulid,
    @Column("permission_type")
    val permissionType: PermissionType,
    @Column("entity_id")
    val entityId: Ulid,
    @Column("user_id")
    val userId: Ulid?,
    @Column("role_id")
    val roleId: Ulid?,
    @Column("permissions")
    val permissions: String,
    @Column("created_date")
    val createdDate: Long,
) {
    fun toEntityPermission(): EntityPermission {
        return EntityPermission(
            permissionId = permissionId,
            permissionType = permissionType,
            entityId = entityId,
            userId = userId,
            roleId = roleId,
            permissions = FilePermission.fromString(permissions),
            createdDate = createdDate
        )
    }
}