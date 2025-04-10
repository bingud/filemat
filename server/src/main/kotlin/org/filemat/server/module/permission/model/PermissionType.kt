package org.filemat.server.module.permission.model

/**
 * ### Indicates whether a permission applies to a user, or a role.
 */
enum class PermissionType {
    USER,
    ROLE;

    companion object {
        fun fromInt(i: Int): PermissionType {
            return PermissionType.entries[i]
                ?: throw IllegalStateException("Invalid integer for PermissionType enum")
        }
    }
}