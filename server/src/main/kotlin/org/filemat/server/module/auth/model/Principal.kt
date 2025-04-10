package org.filemat.server.module.auth.model

import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.Serializable
import org.filemat.server.common.State
import org.filemat.server.config.UlidSerializer
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.role.model.Role

@Serializable
data class Principal(
    @Serializable(UlidSerializer::class)
    val userId: Ulid,
    val email: String,
    val username: String,
    val mfaTotpStatus: Boolean,
    val isBanned: Boolean,
    @Suppress("SERIALIZER_TYPE_INCOMPATIBLE")
    var roles: MutableList<@Serializable(UlidSerializer::class) Ulid>,
) {
    companion object {
        fun Principal.getRoles(): List<Role> {
            return roles.map { State.Auth.roleMap[it] ?: throw IllegalStateException("Role is null") }
        }

        /**
         * Checks if user has permission
         */
        fun Principal.hasPermission(searched: SystemPermission, ignoreSuperAdmin: Boolean = false): Boolean {
            val permissions = this.getPermissions()
            return if (ignoreSuperAdmin) {
                permissions.contains(searched)
            } else {
                permissions.any { perm -> perm == searched || perm == SystemPermission.SUPER_ADMIN }
            }
        }

        /**
         * Returns if user has the input permission
         */
        fun Principal.hasAnyPermission(searched: Collection<SystemPermission>, ignoreSuperAdmin: Boolean = false): Boolean {
            val permissions = this.getPermissions()
            return permissions.any { it in searched || (!ignoreSuperAdmin && it == SystemPermission.SUPER_ADMIN) }
        }

        /**
         * Returns users list of permissions
         */
        fun Principal.getPermissions(): List<SystemPermission> {
            val roles = this.getRoles()
            val permissions = roles.map { it.permissions }.flatten().distinct()
            return permissions
        }
    }
}