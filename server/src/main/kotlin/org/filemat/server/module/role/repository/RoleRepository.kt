package org.filemat.server.module.role.repository

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.module.role.model.Role
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface RoleRepository : CrudRepository<Role, Ulid> {

    @Modifying
    @Query("INSERT INTO role (role_id, name, created_date, permissions) VALUES (:roleId, :name, :createdDate, :permissions)")
    fun insert(roleId: String, name: String, createdDate: Long, permissions: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM role WHERE role_id = :roleId)")
    fun exists(roleId: String): Boolean
}