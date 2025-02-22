package org.filemat.server.module.auth.model

import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.filemat.server.common.State
import org.filemat.server.config.UlidListSerializer
import org.filemat.server.config.UlidSerializer
import org.filemat.server.module.permission.model.Permission
import org.filemat.server.module.role.model.Role
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class Principal(
    @Serializable(UlidSerializer::class)
    val userId: Ulid,
    val email: String,
    val username: String,
    val mfaTotpStatus: Boolean,
    val isBanned: Boolean,
    @Suppress("SERIALIZER_TYPE_INCOMPATIBLE")
    @Serializable(UlidListSerializer::class)
    var roles: MutableList<Ulid>,
) {
    companion object {
        fun Principal.getRoles(): List<Role> {
            return roles.map { State.Auth.roleMap[it] ?: throw IllegalStateException("Role is null") }
        }

        fun Principal.getPermissions(): List<Permission> {
            val roles = this.getRoles()
            val permissions = roles.map { it.permissions }.flatten().distinct()
            return permissions
        }
    }
}