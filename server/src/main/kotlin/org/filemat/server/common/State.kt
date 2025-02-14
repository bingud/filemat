package org.filemat.server.common

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.role.model.Role
import java.util.concurrent.ConcurrentHashMap

object State {
    object App {
        var isSetup: Boolean? = null
        var isInitialized: Boolean = false
        val isDev = env("FM_DEV_MODE")?.toBooleanStrictOrNull() ?: false
    }

    object Auth {
        // Auth token -> User ID
        val tokenToUserIdMap = ConcurrentHashMap<String, Ulid>()
        // User ID -> Principal
        val principalMap = ConcurrentHashMap<Ulid, Principal>()

        // User ID -> User Roles
        val userToRoleMap = ConcurrentHashMap<Ulid, MutableList<Ulid>>()
        // Role ID -> Role
        val roleMap = ConcurrentHashMap<Ulid, Role>()
    }
}

private fun env(name: String): String? {
    val value = System.getenv(name)
    return value
}