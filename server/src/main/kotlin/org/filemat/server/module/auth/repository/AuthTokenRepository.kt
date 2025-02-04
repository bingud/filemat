package org.filemat.server.module.auth.repository

import org.filemat.server.module.auth.model.AuthToken
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface AuthTokenRepository : CrudRepository<AuthToken, String> {



}