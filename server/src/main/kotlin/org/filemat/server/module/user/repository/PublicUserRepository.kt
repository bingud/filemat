package org.filemat.server.module.user.repository

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.module.user.model.MiniUser
import org.filemat.server.module.user.model.PublicUser
import org.filemat.server.module.user.model.User
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface PublicUserRepository : CrudRepository<PublicUser, Ulid> {

    @Query("SELECT user_id, username FROM users WHERE user_id IN (:list)")
    fun getMiniUserList(list: List<Ulid>): List<MiniUser>?

    @Query("SELECT user_id, username FROM users")
    fun getAllMiniUserList(): List<MiniUser>?

}