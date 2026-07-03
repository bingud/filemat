package org.filemat.server.config

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.common.platform.Platform
import org.filemat.server.config.properties.NonDeletableSystemPaths
import org.filemat.server.config.properties.SensitiveFolderPaths
import java.nio.file.Path

/**
 * Contains fixed configuration properties and other constants.
 */
object Props {

    const val appName = "Filemat"
    val dataFolder: String = System.getenv("FM_DATA_DIR")
        ?: if (Platform.isWindows) {
            Path.of(System.getenv("ProgramData") ?: System.getProperty("user.home"), appName).toString()
        } else {
            "/var/lib/filemat"
        }
    val dataFolderPath: Path = Path.of(dataFolder).toAbsolutePath().normalize()
    val databaseFilePath: Path = dataFolderPath.resolve("filemat-server.db")

    val setupCodeFile: String = dataFolderPath.resolve("setup-code.txt").toString()
    val authCodeFile: String = dataFolderPath.resolve("auth-code.txt").toString()
    val defaultUploadFolderPath: String = if (Platform.isWindows) {
        dataFolderPath.resolve("uploads").toString()
    } else {
        "/tmp/filemat"
    }

    val sensitiveFolders = SensitiveFolderPaths
    val nonDeletableFolders = NonDeletableSystemPaths

    /**
     * Holds role-related values
     */
    object Roles {
        val userRoleId = Ulid.from("008BG034N8XRRQ1MKHXNF9Y7RR")
        val adminRoleId = Ulid.from("005QMX54X0AMAS9Z66WXDGY8EX")

        val userRoleIdString = userRoleId.toString()
        val adminRoleIdString = adminRoleId.toString()
    }

    /**
     * Keys for database settings table.
     */
    object Settings {
        const val appSetupCode = "application_setup_code"
        const val isAppSetup = "is_application_setup"
        const val followSymlinks = "follow_symbolic_links"
        const val uploadFolderPath = "upload_folder_path"

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
}