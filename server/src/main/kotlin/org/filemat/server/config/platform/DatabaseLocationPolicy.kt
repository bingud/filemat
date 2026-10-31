package org.filemat.server.config.platform

import java.nio.file.Path

/**
 * Checks that the data folder is somewhere SQLite can safely lock its database.
 */
object DatabaseLocationPolicy {

    /**
     * Returns an error message when [dataFolder] is on a network share or a mapped network drive.
     * SQLite locking over SMB corrupts databases.
     */
    fun check(dataFolder: Path): String? {
        if (!Platform.isWindows) return null

        val raw = dataFolder.toString()
        if (PathPolicy.isUncPath(raw)) {
            return "The data folder ($raw) is a network path. Set FM_DATA_DIR to a folder on a local drive."
        }
        return when (WindowsNative.isOnNetworkDrive(dataFolder)) {
            false -> null
            true -> "The data folder ($raw) is on a network drive. Set FM_DATA_DIR to a folder on a local drive."
            // Without the drive type, network drives can't be told apart, so Filemat doesn't start (fails closed)
            null -> "Filemat can't check whether the data folder ($raw) is on a local drive: ${WindowsNative.unavailableReason ?: "its drive type can't be read."}"
        }
    }
}
