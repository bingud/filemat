package org.filemat.server.common

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.module.auth.model.AuthToken
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
        // All roles
        val roleMap = ConcurrentHashMap<Ulid, Role>()
    }
}

private fun env(name: String): String? {
    val value = System.getenv(name)
    return value
}