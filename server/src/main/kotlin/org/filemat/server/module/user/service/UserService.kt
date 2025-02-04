package org.filemat.server.module.user.service

import jakarta.annotation.PostConstruct
import org.filemat.server.module.user.model.User
import org.filemat.server.module.user.repository.UserRepository
import org.springframework.stereotype.Service

@Service
class UserService(
    private val userRepository: UserRepository,
) {

    fun createUser(user: User) {
        try {

        } catch (e: Exception) {

        }
    }

}