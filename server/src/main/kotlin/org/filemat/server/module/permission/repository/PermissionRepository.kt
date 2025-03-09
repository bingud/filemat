package org.filemat.server.module.permission.repository

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.module.permission.model.EntityPermission
import org.filemat.server.module.permission.model.EntityPermissionDto
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface PermissionRepository : CrudRepository<EntityPermissionDto, Ulid> {

    @Query("SELECT * FROM permissions")
    fun getAll(): List<EntityPermissionDto>

}