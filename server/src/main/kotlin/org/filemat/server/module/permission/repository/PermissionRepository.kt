package org.filemat.server.module.permission.repository

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.module.permission.model.EntityPermissionDto
import org.filemat.server.module.permission.model.PermissionType
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface PermissionRepository : CrudRepository<EntityPermissionDto, Ulid> {

    @Query("SELECT * FROM permissions")
    fun getAll(): List<EntityPermissionDto>

    @Modifying
    @Query("INSERT INTO permissions (permission_id, permission_type, entity_id, user_id, role_id, permissions, created_date) VALUES (:permissionId, :permissionType, :entityId, :userId, :roleId, :permissions, :createdDate)")
    fun insert(
        permissionId: Ulid,
        permissionType: PermissionType,
        entityId: Ulid,
        userId: Ulid?,
        roleId: Ulid?,
        permissions: String,
        createdDate: Long,
    ): Int

    @Modifying
    @Query("UPDATE permissions SET permissions = :newPermissions WHERE permission_id = :permissionId")
    fun updatePermissionList(permissionId: Ulid, newPermissions: String): Int

    @Modifying
    @Query("DELETE FROM permissions WHERE permission_id = :permissionId")
    fun deletePermission(permissionId: Ulid): Int

}