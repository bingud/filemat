package org.filemat.server.common.util.classes.wrappers

/**
 * Hashed password DTO
 */
data class ArgonHash(
    val password: String
) {
    override fun toString(): String {
        return "[Argon Hash]"
    }
}