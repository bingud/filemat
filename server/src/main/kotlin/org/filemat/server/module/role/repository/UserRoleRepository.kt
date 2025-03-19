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
    @Query("INSERT INTO user_roles (role_id, user_id, created_date) VALUES (:roleId, :userId, :now)")
    fun insert(userId: String, roleId: String, now: Long)

    @Query("SELECT * FROM user_roles WHERE user_id = :userId")
    fun getRolesByUserId(userId: Ulid): List<UserRole>

    @Query("SELECT user_id FROM user_roles WHERE role_id = :roleId")
    fun getUserIdsByRole(roleId: Ulid): List<Ulid>?

}