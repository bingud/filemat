package org.filemat.server.module.user.model

import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.Serializable
import org.filemat.server.config.UlidSerializer
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

@Serializable
data class FullPublicUser(
    @Serializable(UlidSerializer::class)
    override val userId: Ulid,
    override val email: String,
    override val username: String,
    override val mfaTotpStatus: Boolean,
    override val createdDate: Long,
    override val lastLoginDate: Long?,
    override val isBanned: Boolean,
    override val roles: List<@Serializable(UlidSerializer::class) Ulid>,
) : AFullPublicUser() {

    companion object {
        fun from(u: PublicUser, roles: List<Ulid>): FullPublicUser {
            return FullPublicUser(
                userId = u.userId,
                email = u.email,
                username = u.username,
                mfaTotpStatus = u.mfaTotpStatus,
                createdDate = u.createdDate,
                lastLoginDate = u.lastLoginDate,
                isBanned = u.isBanned,
                roles = roles
            )
        }
    }

}