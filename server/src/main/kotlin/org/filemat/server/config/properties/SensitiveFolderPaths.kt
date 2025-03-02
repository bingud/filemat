package org.filemat.server.config.properties

import kotlinx.serialization.json.Json
import org.filemat.server.common.State
import org.filemat.server.common.util.normalizePath


/**
 * Blocklist of sensitive system folders
 */
object SensitiveFolderPaths {

    /**
     * List of sensitive folders.
     *
     * Contains stars as wildcards.
     */
    private val fullList = setOf(
        "/etc/shadow",         // Contains password hashes (non-readable without proper rights)
        "/etc/sudoers",        // Critical for controlling sudo access
        "/etc/ssh",            // Houses SSH configuration and host keys
        "/etc/gshadow",        // Contains group password hashes
        "/root",               // Root’s home directory with privileged data
        "/home/*/.ssh",        // User SSH keys and config files
        "/home/*/.gnupg",      // GnuPG keys and sensitive configuration
        "/etc/ssl/private",    // Private TLS/SSL keys crucial for secure communications
        "/var/lib/mysql",      // MySQL data files, if applicable
        "/var/lib/postgresql", // PostgreSQL data files, if applicable
        "/var/lib/docker",     // Docker’s internal data and images
        "/var/run/docker.sock" // Docker socket, which can grant root-equivalent access
    ).filterNot { State.App.nonSensitiveFolders.contains(it) }.toHashSet()


    private val wildcardList = fullList.filter { it.contains("*") }.map { (if (it.startsWith("/")) it else "/$it").split("*") }.map { it[0] to it[1] }
    private val list = fullList.filterNot { it.contains("*") }.toHashSet()

    fun contains(_path: String, isPathNormalized: Boolean): Boolean {
        val path = if (isPathNormalized) _path else normalizePath(_path)
        if (list.any { path.startsWith(it) }) return true

        wildcardList.forEach { pair ->
            if (path.startsWith(pair.first) && path.endsWith(pair.second)) return true
        }
        return false
    }

    private val serializedList: String by lazy { Json.encodeToString(fullList) }
    fun serialize() = serializedList
}












