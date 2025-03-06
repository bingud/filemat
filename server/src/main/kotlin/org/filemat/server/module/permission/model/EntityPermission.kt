package org.filemat.server.module.permission.model

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.module.file.model.FilesystemEntityType
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

@Table("permissions")
data class EntityPermission(
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
    val permissions: List<Permission>,
    @Column("created_date")
    val createdDate: Long,
) {
    init {
        if (userId == null && roleId == null) throw IllegalStateException("Permission must have either a role or user ID.")
    }
}