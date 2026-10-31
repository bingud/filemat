package org.filemat.server.config.properties

import kotlinx.serialization.json.Json
import org.filemat.server.common.util.normalizePath
import org.filemat.server.config.platform.Platform
import org.filemat.server.config.platform.WindowsSystemFolders

/**
 * Defines core system folders that should generally be non-deletable.
 *
 * Root folders (`/` and Windows drive roots) are never deletable, which `FileSecurityService` checks.
 */
object NonDeletableSystemPaths {

    /**
     * List of critical system folders that should not be deleted.
     */
    private val posixList = listOf(
        "/",                   // The root directory itself
        "/bin",                // Essential user command binaries
        "/sbin",               // Essential system binaries
        "/lib",                // Essential shared libraries and kernel modules
        "/lib64",              // Essential 64-bit shared libraries (if applicable)
        "/usr",                // Secondary hierarchy (binaries, libraries, docs, etc.)
        "/etc",                // Host-specific system configuration
        "/boot",               // Static files of the boot loader
        "/dev",                // Device files
        "/proc",               // Virtual filesystem for process/kernel info
        "/sys",                // Virtual filesystem for kernel objects/device tree
        "/var",                // Variable data files (logs, spools, etc.)
        "/run",                // Runtime variable data (since last boot)
        "/opt",                // Optional application software packages
        "/srv",                // Site-specific data served by this system
        "/root",               // Home directory for the root user
        "/home"                // Contains users' home directories (parent folder)
    )

    /**
     * Windows list (§4.2). Contains stars as wildcards.
     */
    internal fun windowsList(folders: WindowsSystemFolders): List<String> {
        val drive = folders.systemDrive
        return listOf(
            folders.systemRoot,
            folders.programData,
            folders.profiles,
            "${folders.profiles}/*",   // Each user profile, including Default and Public
            "$drive/Recovery",
            "$drive/Boot",
            "$drive/EFI",
            "$drive/PerfLogs",
        ) + folders.programFiles
    }

    private val protectedList = if (Platform.isWindows) windowsList(WindowsSystemFolders.current) else posixList

    private val protectedRegex = toExactRegexList(protectedList, ignoreCase = Platform.isWindows)

    /**
     * Checks if the given path exactly matches one of the non-deletable system folders.
     *
     * @param rawPath The path string to check.
     * @param isPathNormalized If true, assumes rawPath is already normalized.
     * @return True if the path matches a non-deletable system folder, false otherwise.
     */
    fun isProtected(rawPath: String, isPathNormalized: Boolean = false): Boolean {
        val path = if (isPathNormalized) rawPath else rawPath.normalizePath()
        return protectedRegex.any { it.matches(path) }
    }

    /**
     * Each pattern matches only its own path. A star matches one segment.
     */
    internal fun toExactRegexList(patterns: Collection<String>, ignoreCase: Boolean): List<Regex> = patterns.map { pattern ->
        val escapedPattern = pattern.split("*").joinToString("[^/]+") { Regex.escape(it) }
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        Regex("^$escapedPattern$", options)
    }

    private val serializedList: String by lazy { Json.encodeToString(protectedList) }

    fun serialize(): String = serializedList

    fun printProtectedFolders() {
        println("### Non-deletable system folders:")
        protectedList.sorted().forEach { println(it) }
        println("\n")
    }
}
