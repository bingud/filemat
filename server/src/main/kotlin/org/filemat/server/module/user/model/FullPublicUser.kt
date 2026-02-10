package org.filemat.server.module.user.model

import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.Serializable
import org.filemat.server.config.UlidSerializer

@Serializable
data class FullPublicUser(
    @Serializable(UlidSerializer::class)
    override val userId: Ulid,
    override val email: String,
    override val username: String,
    override val mfaTotpStatus: Boolean,
    override val mfaTotpRequired: Boolean,
    override val createdDate: Long,
    override val lastLoginDate: Long?,
    override val isBanned: Boolean,
    override val homeFolderPath: String?,
    override val roles: List<@Serializable(UlidSerializer::class) Ulid>,
) : AFullPublicUser() {

    companion object {
        fun from(u: PublicUser, roles: List<Ulid>): FullPublicUser {
            return FullPublicUser(
                userId = u.userId,
                email = u.email,
                username = u.username,
                mfaTotpStatus = u.mfaTotpStatus,
                mfaTotpRequired = u.mfaTotpRequired,
                createdDate = u.createdDate,
                lastLoginDate = u.lastLoginDate,
                isBanned = u.isBanned,
                homeFolderPath = u.homeFolderPath,
                roles = roles,
            )
        }
    }

}