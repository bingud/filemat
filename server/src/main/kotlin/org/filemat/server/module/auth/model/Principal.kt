package org.filemat.server.module.auth.model

import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.filemat.server.common.State
import org.filemat.server.config.UlidListSerializer
import org.filemat.server.config.UlidSerializer
import org.filemat.server.module.role.model.Role

@Serializable
data class Principal(
    @Serializable(UlidSerializer::class)
    val userId: Ulid,
    val email: String,
    val username: String,
    val mfaTotpStatus: Boolean,
    val isBanned: Boolean,
    @Serializable(UlidListSerializer::class)
    val roles: List<Ulid> = resolveRoles(userId)
) {
    companion object {
        private fun resolveRoles(userId: Ulid): List<Ulid> {
            return State.Auth.userToRoleMap[userId]
                ?: throw IllegalStateException("Roles for principal are null.")
        }
    }
}
