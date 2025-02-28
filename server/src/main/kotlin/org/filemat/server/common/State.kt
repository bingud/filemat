package org.filemat.server.common

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.module.role.model.Role
import java.util.concurrent.ConcurrentHashMap

object State {
    object App {
        var isSetup: Boolean = false
        var isInitialized: Boolean = false
        val isDev = env("FM_DEV_MODE")?.toBooleanStrictOrNull() ?: false

        val hideSensitiveFolders = env("FM_HIDE_SENSITIVE_FOLDERS")?.toBooleanStrictOrNull() ?: true
        val hiddenFolders = env("FM_HIDDEN_FOLDER_PATHS")?.split(":")?.map { it.removeSuffix("/") }?.also { println("Hidden folders: $it") }?.toHashSet() ?: hashSetOf( )
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