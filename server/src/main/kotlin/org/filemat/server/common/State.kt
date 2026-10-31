package org.filemat.server.common

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.isAbsoluteHttpsUrl
import org.filemat.server.common.util.normalizePath
import org.filemat.server.common.util.resolvePath
import org.filemat.server.config.Props
import org.filemat.server.config.platform.PathPolicy
import org.filemat.server.config.platform.Platform
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.role.model.Role
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.properties.Delegates

/**
 * Dynamic application state.
 *
 * Contains state and configuration.
 *
 * [env] is the source of environment variables, and [dataFolderPath] the real path of the data folder.
 * Tests can pass their own, so that the real environment and data folder are never used.
 */
@Component
class State(env: Map<String, String?> = System.getenv(), dataFolderPath: Path = Props.dataFolderPath) {
    val app = App(env, dataFolderPath)
    val thumbCache = ThumbCache()
    val auth = Auth()

    /**
     * Real path of one of Filemat's own working folders. Filemat moves and deletes its files inside it without the user checks.
     * Null when the folder is not set or can't be one (see [realInternalFolder]), so the database is never reachable this way.
     */
    fun internalFolderPath(folder: InternalFolder): Path? {
        val configured = when (folder) {
            InternalFolder.UPLOADS -> runCatching { app.uploadFolderPath }.getOrNull()
            InternalFolder.THUMBNAIL_CACHE -> thumbCache.folderPath
        } ?: return null
        return runCatching { realInternalFolder(FilePath.of(configured)).valueOrNull }.getOrNull()
    }

    /**
     * Real path of a folder chosen for Filemat's own working files. Rejects a root, the data folder and the folders that contain it,
     * because Filemat's own moves and deletes there could reach everything inside, the database too.
     */
    fun realInternalFolder(folder: FilePath): Result<Path> {
        val real = resolvePath(folder, followSymlinks = true, allowNonExistent = true).valueOrNull?.path
            ?: return Result.reject("This folder can't be used.")
        if (PathPolicy.isRoot(real)) return Result.reject("A drive or the root folder can't be used.")
        if (app.dataFolderPath.startsWith(real)) return Result.reject("The ${Props.appName} data folder, or a folder that contains it, can't be used.")
        return Result.ok(real)
    }

    /**
     * Returns whether a file is inside one of Filemat's own working folders (not the folder itself).
     * Compared by real path and path key, so neither a link nor a name that differs in case gets in.
     */
    fun isInInternalFolder(folder: InternalFolder, path: Path): Boolean {
        val root = internalFolderPath(folder) ?: return false
        val real = runCatching { path.toRealPath() }.getOrNull() ?: return false
        val key = PathPolicy.pathKey(real)
        val rootKey = PathPolicy.pathKey(root)
        return key != rootKey && PathPolicy.isKeyWithin(key, rootKey)
    }

    class App(env: Map<String, String?>, val dataFolderPath: Path) {
        /**
         * Returns whether a path is the data folder or inside it
         */
        fun isInDataFolder(path: Path) = path.startsWith(dataFolderPath)

        var isSetup: Boolean by Delegates.notNull()
        var isInitialized: Boolean by Delegates.notNull()
        var uploadFolderPath: String by Delegates.notNull()

        val isDev = env.read("FM_DEV_MODE", true)?.toBooleanStrictOrNull() ?: false

        val hideSensitiveFolders = env.read("FM_HIDE_SENSITIVE_FOLDERS", true)?.toBooleanStrictOrNull() ?: true
        val nonSensitiveFolders = env.read("FM_NON_SENSITIVE_FOLDERS").parseFileList { it.print_nonSensitive() }
        // These two compare whole paths, so `*` is no wildcard there
        val hiddenFolders = env.read("FM_HIDDEN_FOLDER_PATHS").parseFileList { it.print_hiddenFolders() }
            .also { list -> wildcardWarnings("FM_HIDDEN_FOLDER_PATHS", list).forEach { println(it) } }
        val forceDeletableFolders = env.read("FM_FORCE_DELETABLE_FOLDERS").parseFileList { it.print_forceDeletable() }
            .also { list -> wildcardWarnings("FM_FORCE_DELETABLE_FOLDERS", list).forEach { println(it) } }

        // Allow Filemat data folder to be accessed
        val allowReadDataFolder = env.read("FM_ALLOW_READ_DATA_FOLDER", true)?.toBooleanStrictOrNull() ?: false
        val allowWriteDataFolder = env.read("FM_ALLOW_WRITE_DATA_FOLDER", true)?.toBooleanStrictOrNull() ?: false

        // Full path of the ffmpeg executable, used where the bundled FFmpeg doesn't load (§7.2)
        val ffmpegPath = env.read("FM_FFMPEG_PATH", true)?.takeIf { it.isNotBlank() }

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

/**
 * Folders that hold Filemat's own working files: uploads in progress and the thumbnail cache
 */
enum class InternalFolder { UPLOADS, THUMBNAIL_CACHE }

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

/**
 * Startup warnings for the entries of [name] that have a `*`, in a list that compares whole paths.
 * Only `FM_NON_SENSITIVE_FOLDERS` takes `*`, because its entries name patterns of the sensitive list.
 */
internal fun wildcardWarnings(name: String, entries: Collection<String>): List<String> = entries.filter { '*' in it }.sorted().map {
    "[!] $name: wildcards (*) are not supported there, so \"$it\" matches no other folder. List each folder by its full path."
}


private fun String?.parseFileList(also: (List<String>) -> Any): HashSet<String> {
    this ?: return hashSetOf()
    return this.split(Platform.listSeparator)
        .filter { it.isNotBlank() }
        // Patterns with `*` are not valid paths on Windows, so only their separators are normalized
        .map { entry -> runCatching { entry.normalizePath() }.getOrElse { entry.replace('\\', '/').trimEnd('/') } }
        .also { also(it) }
        .toHashSet()
}
