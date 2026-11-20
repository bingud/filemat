package org.filemat.server.config.platform

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Tells links apart from other Windows reparse points (§4.4).
 *
 * Java reports every reparse point that is not a symlink as "other": junctions and volume mount points,
 * but also OneDrive placeholders and deduplicated files, which hold ordinary file data.
 */
object LinkInspector {
    // IsReparseTagNameSurrogate: the tag points to another file or folder
    private const val nameSurrogateBit = 0x20000000

    // FILE_ATTRIBUTE_RECALL_ON_OPEN and FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS: the data is not on this PC, and reading it downloads it
    private const val recallAttributes = 0x00040000 or 0x00400000

    enum class Kind {
        /** Not a reparse point, or not on Windows */
        NONE,
        /** A symlink, junction or volume mount point */
        LINK,
        /** A reparse point with file data, like a OneDrive placeholder. Reading it may download the file. */
        DATA,
    }

    /**
     * Returns the kind of a path from its attributes, read without following links
     */
    fun kindOf(path: Path, attributes: BasicFileAttributes): Kind {
        if (attributes.isSymbolicLink) return Kind.LINK
        if (!Platform.isWindows) return Kind.NONE
        // Windows disguises OneDrive placeholders as ordinary files for programs that don't opt in, Java among them:
        // no reparse point and no tag. The recall attributes stay visible.
        if (!attributes.isOther) return if (!attributes.isDirectory && isRecalledOnAccess(path)) Kind.DATA else Kind.NONE
        // A folder whose tag can't be read counts as a link, so walks never descend into an unknown target
        val tag = WindowsNative.reparseTag(path) ?: return if (attributes.isDirectory) Kind.LINK else Kind.NONE
        return when {
            tag == 0 -> Kind.NONE
            (tag and nameSurrogateBit) != 0 -> Kind.LINK
            else -> Kind.DATA
        }
    }

    fun kindOf(path: Path): Kind {
        val attributes = readAttributes(path) ?: return Kind.NONE
        return kindOf(path, attributes)
    }

    /**
     * Returns whether a path is a folder link that Java reports as a directory: a junction or volume mount point.
     *
     * Java reports these as directories even with `NOFOLLOW_LINKS`, so recursive operations must not descend into them.
     * Descending would delete, move or copy the contents of the link target instead of the link itself.
     * OneDrive folders are reparse points too, but not links, so they are walked like folders.
     */
    fun isDirectoryLink(path: Path): Boolean {
        val attributes = readAttributes(path) ?: return false
        return attributes.isDirectory && attributes.isOther && kindOf(path, attributes) == Kind.LINK
    }

    /**
     * Returns whether a path is a OneDrive placeholder or another reparse point with data. These are never previewed, because reading them can download the file (Q42).
     */
    fun isNotStoredLocally(path: Path): Boolean = kindOf(path) == Kind.DATA

    /**
     * Returns whether a path is a file with data, including reparse points like OneDrive placeholders that Java does not count as regular files.
     * Links are not followed.
     */
    fun isRegularFile(path: Path): Boolean {
        val attributes = readAttributes(path) ?: return false
        if (attributes.isRegularFile) return true
        return !attributes.isDirectory && kindOf(path, attributes) == Kind.DATA
    }

    private fun readAttributes(path: Path): BasicFileAttributes? = runCatching {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    }.getOrNull()

    private fun isRecalledOnAccess(path: Path): Boolean = ((windowsAttributes(path) ?: 0) and recallAttributes) != 0

    /**
     * Windows: the file attributes of [path] (`FILE_ATTRIBUTE_*`), read without following links or reading the file's data
     */
    internal fun windowsAttributes(path: Path): Int? = runCatching {
        Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS) as Int
    }.getOrNull()
}
