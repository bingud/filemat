package org.filemat.server.common.util.classes

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