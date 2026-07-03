package org.filemat.server.config.properties

import kotlinx.serialization.json.Json
import org.filemat.server.common.platform.Platform
import org.filemat.server.common.util.normalizePath
import org.filemat.server.config.Props

/**
 * Defines core system folders that should generally be non-deletable.
 */
object NonDeletableSystemPaths {

    /**
     * List of critical system folders that should not be deleted.
     */
    private val linuxProtectedList = setOf(
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

    private val windowsProtectedList = setOf(
        "C:/",
        "C:/Windows",
        "C:/Windows/System32",
        "C:/Program Files",
        "C:/Program Files (x86)",
        "C:/ProgramData",
        "C:/System Volume Information",
        "C:/${'$'}Recycle.Bin",
        Props.dataFolderPath.toString(),
    )

    private val protectedList = if (Platform.isWindows) windowsProtectedList else linuxProtectedList

    private val protectedSetNormalized = protectedList.map { it.normalizePath() }.toHashSet()

    /**
     * Checks if the given path exactly matches one of the non-deletable system folders.
     *
     * @param rawPath The path string to check.
     * @param isPathNormalized If true, assumes rawPath is already normalized.
     * @return True if the path matches a non-deletable system folder, false otherwise.
     */
    fun isProtected(rawPath: String, isPathNormalized: Boolean = false): Boolean {
        val path = if (isPathNormalized) rawPath else rawPath.normalizePath()
        // Check for an exact match against the normalized protected paths
        return protectedSetNormalized.contains(path)
    }


    private val serializedList: String by lazy { Json.encodeToString(protectedList) }

    fun serialize(): String = serializedList

    fun printProtectedFolders() {
        println("### Non-deletable system folders:")
        protectedList.sorted().forEach { println(it) }
        println("\n")
    }
}