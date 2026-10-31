package org.filemat.server.config.platform

import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32Util

/**
 * Windows system folders, read from environment variables and Known Folders instead of assuming `C:` (§4.1).
 * Paths use `/`, like API paths.
 */
data class WindowsSystemFolders(
    val systemDrive: String,
    val systemRoot: String,
    val programData: String,
    val programFiles: List<String>,
    /**
     * Folder that holds the user profiles, usually `C:/Users`
     */
    val profiles: String,
) {
    companion object {
        /**
         * Folders of this computer. Only read it on Windows.
         */
        val current: WindowsSystemFolders by lazy {
            val profiles = runCatching { Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_UserProfiles) }.getOrNull()
            of(System.getenv(), profiles)
        }

        /**
         * Missing variables fall back to the default folders on the system drive
         */
        fun of(env: Map<String, String>, profiles: String?): WindowsSystemFolders {
            fun read(name: String) = env[name]?.takeIf { it.isNotBlank() }?.let(::toApiForm)
            val drive = read("SystemDrive") ?: "C:"
            return WindowsSystemFolders(
                systemDrive = drive,
                systemRoot = read("SystemRoot") ?: "$drive/Windows",
                programData = read("ProgramData") ?: "$drive/ProgramData",
                programFiles = listOfNotNull(read("ProgramFiles"), read("ProgramFiles(x86)"), read("ProgramW6432"))
                    .distinctBy { it.lowercase() }
                    .ifEmpty { listOf("$drive/Program Files", "$drive/Program Files (x86)") },
                profiles = profiles?.takeIf { it.isNotBlank() }?.let(::toApiForm) ?: "$drive/Users",
            )
        }

        private fun toApiForm(path: String) = path.replace('\\', '/').trimEnd('/')
    }
}
