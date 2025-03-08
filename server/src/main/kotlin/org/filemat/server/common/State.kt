package org.filemat.server.common

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.common.util.normalizePath
import org.filemat.server.module.role.model.Role
import java.util.concurrent.ConcurrentHashMap

/**
 * Dynamic application state
 */
object State {
    object App {
        var isSetup: Boolean = false
        var isInitialized: Boolean = false
        val isDev = env("FM_DEV_MODE")?.toBooleanStrictOrNull() ?: false

        val hideSensitiveFolders = env("FM_HIDE_SENSITIVE_FOLDERS")?.toBooleanStrictOrNull() ?: true
        val nonSensitiveFolders = env("FM_NON_SENSITIVE_FOLDERS").getNonSensitiveFolders()
        val hiddenFolders = env("FM_HIDDEN_FOLDER_PATHS").getHiddenFolders()

        private val followSymLinksEnv = env("FM_FOLLOW_SYMBOLIC_LINKS")?.toBooleanStrictOrNull() ?: false
        var followSymLinks: Boolean = followSymLinksEnv
            set(new) {
                if (followSymLinksEnv == true) return
                field = new
            }

        val printLogs = env("FM_PRINT_LOGS")?.toBooleanStrictOrNull() ?: true
    }

    object Auth {
        // All roles
        val roleMap = ConcurrentHashMap<Ulid, Role>()
    }
}

private fun env(name: String): String?= System.getenv(name)


/**
 * Environment variable helper functions
 */

private fun String?.getNonSensitiveFolders(): HashSet<String> {
    this ?: return hashSetOf()
    return this.split(":")
        .map { it.removeSuffix("/") }
        .toHashSet()
        .also { println("Some folders were marked as not sensitive:\n${it.joinToString("\n")}") }
}

private fun String?.getHiddenFolders(): HashSet<String> {
    this ?: return hashSetOf()
    return this.split(":")
        .map { it.normalizePath() }
        .also { println("Hidden folders:\n${it.joinToString("\n")}") }.toHashSet()
}