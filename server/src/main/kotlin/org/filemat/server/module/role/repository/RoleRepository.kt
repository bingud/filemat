package org.filemat.server.module.role.repository

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.module.role.model.Role
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface RoleRepository : CrudRepository<Role, Ulid> {



}