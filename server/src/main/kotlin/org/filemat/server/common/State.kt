package org.filemat.server.common

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.common.util.isAbsoluteHttpsUrl
import org.filemat.server.common.util.normalizePath
import org.filemat.server.module.role.model.Role
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.properties.Delegates

/**
 * Dynamic application state.
 *
 * Contains state and configuration.
 *
 * [env] is the source of environment variables. Tests can pass their own map
 * so that the real environment is never read.
 */
@Component
class State(env: Map<String, String?> = System.getenv()) {
    val app = App(env)
    val thumbCache = ThumbCache()
    val auth = Auth()

    class App(env: Map<String, String?>) {
        var isSetup: Boolean by Delegates.notNull()
        var isInitialized: Boolean by Delegates.notNull()
        var uploadFolderPath: String by Delegates.notNull()

        val isDev = env.read("FM_DEV_MODE", true)?.toBooleanStrictOrNull() ?: false

        val hideSensitiveFolders = env.read("FM_HIDE_SENSITIVE_FOLDERS", true)?.toBooleanStrictOrNull() ?: true
        val nonSensitiveFolders = env.read("FM_NON_SENSITIVE_FOLDERS").parseFileList { it.print_nonSensitive() }
        val hiddenFolders = env.read("FM_HIDDEN_FOLDER_PATHS").parseFileList { it.print_hiddenFolders() }
        val forceDeletableFolders = env.read("FM_FORCE_DELETABLE_FOLDERS").parseFileList { it.print_forceDeletable() }

        // Allow Filemat data folder to be accessed
        val allowReadDataFolder = env.read("FM_ALLOW_READ_DATA_FOLDER", true)?.toBooleanStrictOrNull() ?: false
        val allowWriteDataFolder = env.read("FM_ALLOW_WRITE_DATA_FOLDER", true)?.toBooleanStrictOrNull() ?: false

        private val followSymLinksEnv = (env.read("FM_FOLLOW_SYMBOLIC_LINKS", true)?.toBooleanStrictOrNull())
        var followSymlinks: Boolean = (followSymLinksEnv ?: false).also { println("Follow symbolic links: $it\n") }
            set(new) {
                // Environment variable overrides setting
                if (followSymLinksEnv != null) return
                field = new
            }

        val contentBaseUrl = ContentBaseUrl(env)

        class ContentBaseUrl(env: Map<String, String?>) {
            private val urlEnv = contentBaseUrlFromEnv(env.read("FM_CONTENT_BASE_URL", true))
            val lockedByEnv get() = urlEnv != null
            var url: String = (urlEnv ?: "").also {
                if (urlEnv != null) println("Content base URL (env): $it\n")
            }
                set(new) {
                    if (urlEnv != null) return
                    field = new
                }

            private val forUnauthenticatedEnv = env.read("FM_CONTENT_BASE_URL_FOR_UNAUTHENTICATED", true)?.toBooleanStrictOrNull()
            val forUnauthenticatedLockedByEnv get() = forUnauthenticatedEnv != null
            var forUnauthenticated: Boolean = (forUnauthenticatedEnv ?: false).also {
                if (forUnauthenticatedEnv != null) println("Content base URL for unauthenticated users (env): $it\n")
            }
                set(new) {
                    if (forUnauthenticatedEnv != null) return
                    field = new
                }
        }

        val printLogs = env.read("FM_PRINT_LOGS", true)?.toBooleanStrictOrNull() ?: true
    }

    class ThumbCache {
        var isEnabled: Boolean by Delegates.notNull()
        var folderPath: String? = null
        var maxSizeMb: Int? = null

        /**
         * [maxAge] in milliseconds for comparisons with [System.currentTimeMillis]; kept in sync
         * by the [maxAge] setter.
         */
        var maxAgeMs: Long? = null
            private set

        /**
         * Maximum cache entry age in **seconds** (persisted setting). Assigning this updates
         * [maxAgeMs] so readers do not convert on every use.
         */
        var maxAge: Long? = null
            set(value) {
                field = value
                maxAgeMs = value?.let { Duration.ofSeconds(it).toMillis().coerceAtLeast(0L) }
            }
    }

    class Auth {
        // All roles
        val roleMap = ConcurrentHashMap<Ulid, Role>()
    }
}

private fun contentBaseUrlFromEnv(raw: String?): String? {
    val value = raw?.trim() ?: return null
    if (value.isEmpty()) return null
    if (!value.isAbsoluteHttpsUrl()) {
        throw IllegalArgumentException("FM_CONTENT_BASE_URL must be an absolute https URL.")
    }
    return value
}

private fun Map<String, String?>.read(name: String, print: Boolean = false): String? {
    val value = this[name]
    val cleanName = name.removePrefix("FM_")

    if (print && value != null) println("---\nEnvironment variable: $cleanName\n$value\n---")

    return value
}


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
