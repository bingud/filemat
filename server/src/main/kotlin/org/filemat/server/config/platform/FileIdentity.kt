package org.filemat.server.config.platform

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration

/**
 * Identity of a file that survives renames and moves within a volume (§3).
 *
 * POSIX: `posix:<dev>:<ino>`. Windows: `win:<volumeSerial>:<fileId>`, the NTFS and ReFS file ID.
 * Links are not followed, so a link has its own key.
 */
object FileIdentity {
    // Volumes can be swapped (USB drives), so support expires
    private val volumeSupport: Cache<String, Boolean> = Caffeine.newBuilder()
        .maximumSize(1_000)
        .expireAfterWrite(Duration.ofMinutes(1))
        .build()

    // Folders shared from a Windows or macOS host (Docker Desktop, WSL), where inode numbers may change after a restart
    private val unstableMountTypes = setOf("9p", "drvfs", "virtiofs", "fakeowner", "grpcfuse", "fuse.grpcfuse", "osxfs", "fuse.osxfs")
    // Mount fields write spaces, tabs and backslashes as octal escapes, such as \040
    private val mountEscape = Regex("""\\([0-7]{3})""")

    /**
     * Returns the file key of a path, or null when it can't be read
     */
    fun fileKey(path: Path): String? {
        // The drive list is no file. Windows would open the current drive for `\`, and an entity there would take its key.
        if (PathPolicy.isVirtualRoot(path)) return null
        if (Platform.isWindows) return WindowsNative.fileId(path)?.let { "win:$it" }

        return runCatching {
            val attributes = Files.readAttributes(path, "unix:dev,ino", LinkOption.NOFOLLOW_LINKS)
            val dev = attributes["dev"] ?: return null
            val ino = attributes["ino"] ?: return null
            "posix:$dev:$ino"
        }.getOrNull()
    }

    /**
     * Returns whether file keys on the volume of a path are stable. Null when the path does not exist.
     *
     * FAT, exFAT and network volumes are not, so entities there follow the path only.
     */
    fun isSupported(path: Path): Boolean? {
        if (PathPolicy.isVirtualRoot(path)) return false
        if (!Files.exists(path)) return null

        if (!Platform.isWindows) {
            return runCatching { Files.getFileStore(path).supportsFileAttributeView("unix") }.getOrDefault(false)
        }

        val root = WindowsNative.volumeRoot(path) ?: return false
        volumeSupport.getIfPresent(root)?.let { return it }

        val supported = WindowsNative.hasStableFileIds(root) ?: return false
        volumeSupport.put(root, supported)
        return supported
    }

    /**
     * Returns the filesystem type of a path when its inode numbers may change after a restart, otherwise null.
     *
     * Linux only, from `/proc/self/mounts`.
     */
    fun unstableMountType(path: Path): String? {
        if (Platform.isWindows) return null
        val mounts = runCatching { Files.readAllLines(Path.of("/proc/self/mounts")) }.getOrNull() ?: return null
        return unstableMountType(path.toString(), mounts)
    }

    internal fun unstableMountType(path: String, mounts: List<String>): String? {
        var mountPoint = ""
        var type: String? = null
        for (line in mounts) {
            val fields = line.split(' ')
            if (fields.size < 3) continue
            val point = fields[1].replace(mountEscape) { it.groupValues[1].toInt(8).toChar().toString() }
            val contains = point == "/" || path == point || path.startsWith("$point/")
            // A later mount on the same point hides the earlier one
            if (contains && point.length >= mountPoint.length) {
                mountPoint = point
                type = fields[2]
            }
        }
        return type?.takeIf { it in unstableMountTypes }
    }
}
