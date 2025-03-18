package org.filemat.server.module.user.repository

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.module.user.model.PublicUser
import org.filemat.server.module.user.model.User
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface PublicUserRepository : CrudRepository<PublicUser, Ulid> {
}