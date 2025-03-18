package org.filemat.server.module.user.model

import com.github.f4b6a3.ulid.Ulid

/**
 * Base user class, with only client-visible data.
 */
abstract class APublicUser {
    abstract val userId: Ulid
    abstract val email: String
    abstract val username: String
    abstract val mfaTotpStatus: Boolean
    abstract val createdDate: Long
    abstract val lastLoginDate: Long?
    abstract val isBanned: Boolean
}

/**
 * Full user class
 */
abstract class AUser : APublicUser() {
    abstract val password: String
    abstract val mfaTotpSecret: String?
    abstract val mfaTotpCodes: List<String>?
}

/**
 * Client-visible user class with additional metadata
 */
abstract class AFullPublicUser : APublicUser() {
    abstract val roles: List<Ulid>
}