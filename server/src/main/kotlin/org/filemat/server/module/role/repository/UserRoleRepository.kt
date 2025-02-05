package org.filemat.server.module.role.repository

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.module.role.model.Role
import org.filemat.server.module.role.model.UserRole
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface UserRoleRepository : CrudRepository<UserRole, Ulid> {

    @Modifying
    @Query("INSERT INTO user_roles (role_id, user_id, created_date) VALUES (:userId, :roleId, :now)")
    fun insert(userId: String, roleId: String, now: Long): Int

}