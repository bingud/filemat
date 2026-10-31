package org.filemat.server.config.properties

import kotlinx.serialization.json.Json
import org.filemat.server.common.State
import org.filemat.server.common.util.normalizePath
import org.filemat.server.config.platform.PathPolicy
import org.filemat.server.config.platform.Platform
import org.filemat.server.config.platform.WindowsSystemFolders
import org.springframework.stereotype.Component


/**
 * Blocklist of sensitive system folders
 */
@Component
class SensitiveFolderPaths(private val state: State) {

    /**
     * List of sensitive folders.
     *
     * Contains stars as wildcards.
     */
    private val fullList = (if (Platform.isWindows) windowsList(WindowsSystemFolders.current) else posixList)
        .filterNot { pattern -> state.app.nonSensitiveFolders.any { it.equals(pattern, ignoreCase = Platform.isWindows) } }
        .toHashSet()
        .also { if (!state.app.allowReadDataFolder) it.add(PathPolicy.toApiString(state.app.dataFolderPath)) }


    private val regexList: List<Regex> by lazy { toSubtreeRegexList(fullList, ignoreCase = Platform.isWindows) }

    fun contains(rawPath: String, isPathNormalized: Boolean): Boolean {
        val path = if (isPathNormalized) rawPath else rawPath.normalizePath()
        return regexList.any { it.matches(path) }
    }

    private val serializedList: String by lazy { Json.encodeToString(fullList) }
    fun serialize() = serializedList

    fun printSensitiveFolders() {
        println("### Sensitive folders that will be blocked:")
        fullList.forEach { println(it) }
        println("\n")
    }

    companion object {
        private val posixList = listOf(
            "/etc/shadow",           // Contains password hashes (non-readable without proper rights)
            "/etc/sudoers",          // Critical for controlling sudo access
            "/etc/ssh",              // Houses SSH configuration and host keys
            "/etc/gshadow",          // Contains group password hashes
            "/root",                 // Root’s home directory with privileged data
            "/home/*/.ssh",          // User SSH keys and config files
            "/home/*/.gnupg",        // GnuPG keys and sensitive configuration
            "/etc/ssl/private",      // Private TLS/SSL keys crucial for secure communications
            "/var/lib/mysql",        // MySQL data files, if applicable
            "/var/lib/postgresql",   // PostgreSQL data files, if applicable
            "/var/lib/docker",       // Docker’s internal data and images
            "/var/run/docker.sock",  // Docker socket, which can grant root-equivalent access
        )

        /**
         * Windows list (§4.1). Only parts of AppData are blocked: credentials, keys and browser profiles (Q60).
         */
        internal fun windowsList(folders: WindowsSystemFolders): List<String> {
            val system = folders.systemRoot
            val data = folders.programData
            val profile = "${folders.profiles}/*"
            return listOf(
                "$system/System32/config",                          // Registry hives with password hashes, and the system account's profile
                "$system/System32/Microsoft/Protect",               // DPAPI keys of the system
                "$system/NTDS",                                     // Active Directory database on domain controllers
                "$system/repair",                                   // Old registry backups
                "$system/Panther",                                  // Setup files, which can hold admin passwords
                "$system/System32/Sysprep",
                "$system/ServiceProfiles",                          // Profiles of service accounts
                "$system/System32/inetsrv/config",                  // IIS configuration with credentials
                "$system/MEMORY.DMP",                               // Crash dumps hold memory contents
                "$system/CSC",                                      // Offline Files: local copies of network files
                "$data/Microsoft/Crypto",                           // Machine private keys
                "$data/Microsoft/Windows/WER",                      // Crash dumps
                "$data/Docker",
                "$data/MySQL",
                "$data/ssh",                                        // OpenSSH server host keys
                "$profile/NTUSER.DAT*",                             // User registry hives
                "$profile/.ssh",
                "$profile/.gnupg",
                "$profile/.aws",
                "$profile/.azure",
                "$profile/.kube",
                "$profile/.docker",
                "$profile/.git-credentials",                        // Saved passwords of Git and other tools
                "$profile/_netrc",
                "$profile/.netrc",
                "$profile/AppData/Roaming/Microsoft/Credentials",   // Saved credentials and the DPAPI keys that decrypt them
                "$profile/AppData/Roaming/Microsoft/Protect",
                "$profile/AppData/Roaming/Microsoft/Crypto",
                "$profile/AppData/Roaming/Microsoft/SystemCertificates",
                "$profile/AppData/Local/Microsoft/Credentials",
                "$profile/AppData/Local/Microsoft/Vault",
                "$profile/AppData/Local/Microsoft/TokenBroker",     // Microsoft account and work account sign-in tokens
                "$profile/AppData/Local/Microsoft/IdentityCache",
                "$profile/AppData/Local/Google/Chrome/User Data",   // Browser profiles with saved passwords and cookies
                "$profile/AppData/Local/Microsoft/Edge/User Data",
                "$profile/AppData/Roaming/Mozilla/Firefox/Profiles",
                "$profile/AppData/Local/BraveSoftware",
                "$profile/AppData/Local/Chromium",
                "$profile/AppData/Local/Vivaldi",
                "$profile/AppData/Roaming/Opera Software",
                "$profile/AppData/Roaming/Thunderbird/Profiles",    // Mail profiles with saved passwords
                "$profile/AppData/Roaming/Microsoft/Windows/PowerShell/PSReadLine", // Command history, which can hold typed secrets
                "*:/System Volume Information",                     // Shadow copies and restore points
                "*:/\$Recycle.Bin",                                 // Deleted files of every user
                "*:/\$Extend",
                "*:/pagefile.sys",                                  // Memory written to disk
                "*:/hiberfil.sys",
                "*:/swapfile.sys",
                "*:/DumpStack.log*",
            ) + folders.programFiles.map { "$it/PostgreSQL/*/data" }
        }

        /**
         * Each pattern matches its path and everything inside it. A star matches within one segment.
         */
        internal fun toSubtreeRegexList(patterns: Collection<String>, ignoreCase: Boolean): List<Regex> = patterns.map { pattern ->
            val segments = pattern.split("*")
            val escapedPattern = segments.joinToString("[^/]*") { Regex.escape(it) }
            val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            Regex("^$escapedPattern($|/.*)", options)
        }
    }
}
