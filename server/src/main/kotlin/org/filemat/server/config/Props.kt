package org.filemat.server.config

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.config.properties.NonDeletableSystemPaths
import org.filemat.server.config.properties.SensitiveFolderPaths

/**
 * Contains fixed configuration properties and other constants.
 */
object Props {

    const val appName = "Filemat"
    const val dataFolder = "/var/lib/filemat"
    const val setupCodeFile = "$dataFolder/setup-code.txt"
    const val authCodeFile = "$dataFolder/auth-code.txt"
    const val defaultUploadFolderPath = "/tmp/filemat"

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
    }

    object Cookies {
        const val tempLoginToken = "filemat-temp-login-token"
        const val authToken = "filemat-auth-token"
    }
}