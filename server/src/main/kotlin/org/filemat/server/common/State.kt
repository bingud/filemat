package org.filemat.server.common

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.common.util.normalizePath
import org.filemat.server.module.role.model.Role
import java.util.concurrent.ConcurrentHashMap
import kotlin.properties.Delegates

/**
 * Dynamic application state.
 *
 * Contains state and configuration.
 */
object State {
    object App {
        var isSetup: Boolean by Delegates.notNull()
        var isInitialized: Boolean by Delegates.notNull()
        var uploadFolderPath: String by Delegates.notNull()

        val isDev = env("FM_DEV_MODE")?.toBooleanStrictOrNull() ?: false

        val hideSensitiveFolders = env("FM_HIDE_SENSITIVE_FOLDERS")?.toBooleanStrictOrNull() ?: true
        val nonSensitiveFolders = env("FM_NON_SENSITIVE_FOLDERS").parseFileList { it.print_nonSensitive() }
        val hiddenFolders = env("FM_HIDDEN_FOLDER_PATHS").parseFileList { it.print_hiddenFolders() }
        val forceDeletableFolders = env("FM_FORCE_DELETABLE_FOLDERS").parseFileList { it.print_forceDeletable() }

        // Allow Filemat data folder to be accessed
        val allowReadDataFolder = env("FM_ALLOW_RED_DATA_FOLDER")?.toBooleanStrictOrNull() ?: false
        val allowWriteDataFolder = env("FM_ALLOW_WRITE_DATA_FOLDER")?.toBooleanStrictOrNull() ?: false

        private val followSymLinksEnv = (env("FM_FOLLOW_SYMBOLIC_LINKS")?.toBooleanStrictOrNull()).also { println("Follow symbolic links: $it\n") }
        var followSymLinks: Boolean = followSymLinksEnv ?: false
            set(new) {
                // Environment variable overrides setting
                if (followSymLinksEnv != null) return
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

fun List<String>.print_nonSensitive() = println("Some folders were marked as not sensitive:\n${this.joinToString("\n")}")
fun List<String>.print_hiddenFolders() = println("Hidden folders:\n${this.joinToString("\n")}")
fun List<String>.print_forceDeletable() = println("Folders that were made deletable:\n${this.joinToString("\n")}")


private fun String?.parseFileList(also: (List<String>) -> Any): HashSet<String> {
    this ?: return hashSetOf()
    return this.split(":")
        .map { it.normalizePath() }
        .also { also(it) }
        .toHashSet()
}