package org.filemat.server.config

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.config.platform.Platform
import org.filemat.server.config.properties.NonDeletableSystemPaths
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Contains fixed configuration properties and other constants.
 */
object Props {

    const val appName = "Filemat"

    /**
     * Data folder as configured with `FM_DATA_DIR`, made absolute.
     * Defaults to `%LOCALAPPDATA%\Filemat` on Windows and `/var/lib/filemat` elsewhere.
     */
    val configuredDataFolderPath: Path = resolveDataFolder(
        env = System.getenv(),
        isWindows = Platform.isWindows,
        userHome = System.getProperty("user.home"),
    )

    /**
     * Data folder with links resolved. Read it after `initializeFileStructure` has created the folder.
     * Services read it from `State.app.dataFolderPath`, so tests can use their own folder.
     */
    val dataFolderPath: Path by lazy {
        runCatching { configuredDataFolderPath.toRealPath() }.getOrDefault(configuredDataFolderPath)
    }

    val databaseFile: Path = configuredDataFolderPath.resolve("filemat-server.db")
    val setupCodeFile: String get() = dataFolderPath.resolve("setup-code.txt").toString()
    val authCodeFile: String get() = dataFolderPath.resolve("auth-code.txt").toString()

    /**
     * Windows keeps uploads and the thumbnail cache in the data folder, because Storage Sense purges temp folders.
     * Built from the real data folder, like every check against it.
     */
    val defaultUploadFolderPath: String get() =
        if (Platform.isWindows) dataFolderPath.resolve("uploads").toString() else "/tmp/filemat"
    val defaultThumbnailCacheFolderPath: String get() =
        if (Platform.isWindows) dataFolderPath.resolve("cache").resolve("thumbnails").toString() else "/var/cache/filemat"
    const val defaultThumbnailCacheMaxSizeMb = 512
    const val defaultThumbnailCacheMaxAgeSeconds = 604800L

    /**
     * A getter, so reading `Props` doesn't load the Windows list, which calls JNA before `main` has set `jna.tmpdir`
     */
    val nonDeletableFolders get() = NonDeletableSystemPaths

    /**
     * Holds role-related values
     */
    object Roles {
        val userRoleId = Ulid.from("008BG034N8XRRQ1MKHXNF9Y7RR")
        val adminRoleId = Ulid.from("005QMX54X0AMAS9Z66WXDGY8EX")

        val userRoleIdString = userRoleId.toString()
        val adminRoleIdString = adminRoleId.toString()

        fun isSystemRole(roleId: Ulid) = roleId == userRoleId || roleId == adminRoleId
    }

    /**
     * Keys for database settings table.
     */
    object Settings {
        const val appSetupCode = "application_setup_code"
        const val isAppSetup = "is_application_setup"
        const val followSymlinks = "follow_symbolic_links"
        const val uploadFolderPath = "upload_folder_path"
        const val contentBaseUrl = "content_base_url"
        const val contentBaseUrlForUnauthenticated = "content_base_url_for_unauthenticated"

        object ThumbCache {
            const val enabled = "is_thumbnail_cache_enabled"
            const val folderPath = "thumbnail_cache_folder_path"
            const val maxSizeMb = "thumbnail_cache_max_size_mb"
            const val maxAge = "thumbnail_cache_max_age"
        }
    }

    object Cookies {
        const val tempLoginToken = "filemat-temp-login-token"
        const val authToken = "filemat-auth-token"
    }

    object ContentSession {
        const val ticketTtlSeconds = 60L
        const val cookieMaxAgeSeconds = 3600L
    }

    /**
     * Resolves the data folder from `FM_DATA_DIR`, or the OS default when it is not set
     */
    internal fun resolveDataFolder(env: Map<String, String>, isWindows: Boolean, userHome: String): Path {
        val raw = env["FM_DATA_DIR"]?.takeIf { it.isNotBlank() }
            ?: if (isWindows) {
                val localAppData = env["LOCALAPPDATA"]?.takeIf { it.isNotBlank() }
                    ?: Path.of(userHome, "AppData", "Local").toString()
                Path.of(localAppData, appName).toString()
            } else {
                "/var/lib/filemat"
            }

        return try {
            Path.of(raw).toAbsolutePath().normalize()
        } catch (e: InvalidPathException) {
            throw IllegalArgumentException("FM_DATA_DIR is not a valid path: $raw", e)
        }
    }
}