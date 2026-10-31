package org.filemat.server.testutil

import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Runs [block] while [path] is open the way java.io opens files, without share-delete.
 * Windows then refuses to delete, rename or move it, like a file another program has open.
 */
fun <T> withFileOpen(path: Path, block: () -> T): T = FileInputStream(path.toFile()).use { block() }

/**
 * Runs [block] while [path] is locked. On Windows, locks are mandatory, so other handles can't write to it.
 */
fun <T> withFileLocked(path: Path, block: () -> T): T = FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
    channel.lock().use { block() }
}

/**
 * Sets or clears the Windows read-only attribute
 */
fun setReadOnly(path: Path, readOnly: Boolean = true) {
    Files.setAttribute(path, "dos:readonly", readOnly)
}

/**
 * Returns whether [path] has the Windows read-only attribute, of a folder too. This is the plain attribute, unlike
 * `FileErrors.isReadOnlyFile`, which doesn't follow links and is false for folders. Throws on a system without DOS attributes.
 */
fun isReadOnly(path: Path): Boolean = Files.getAttribute(path, "dos:readonly") as Boolean

/**
 * Returns whether [path] has the Windows hidden attribute. Throws on a system without DOS attributes.
 */
fun isHidden(path: Path): Boolean = Files.getAttribute(path, "dos:hidden") as Boolean
