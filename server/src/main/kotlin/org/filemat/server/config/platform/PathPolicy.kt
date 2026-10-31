package org.filemat.server.config.platform

import kotlinx.serialization.Serializable
import org.filemat.server.common.model.Result
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Converts between API path strings and native paths, and holds the path rules of the server OS.
 *
 * API paths always use `/` as the separator and start at [root].
 * Call sites use the companion (`PathPolicy.parse(...)`), which delegates to the implementation of the current OS.
 */
interface PathPolicy {
    /**
     * API path of the root folder
     */
    val root: String

    /**
     * Rules for a single file or folder name
     */
    val filenameRules: FilenameRules

    /**
     * Parses an API path string into a normalized absolute native path
     */
    fun parse(apiPath: String): Path

    /**
     * Normalizes a native path and makes it absolute
     */
    fun normalize(path: Path): Path

    /**
     * Converts a native path to an API path string
     */
    fun toApiString(path: Path): String

    /**
     * Returns whether a path has no parent: the root folder, or a drive root on Windows
     */
    fun isRoot(path: Path): Boolean

    /**
     * Returns whether a path is the root folder.
     * On Windows the root is the virtual list of drives, which is not a real folder.
     */
    fun isVirtualRoot(path: Path): Boolean

    /**
     * Folders shown in the virtual root. Empty when the root is a real folder.
     */
    fun virtualRootEntries(): List<Path>

    /**
     * Returns whether two native paths name the same file, compared the way the OS does
     */
    fun isSamePath(a: Path, b: Path): Boolean = a == b

    /**
     * Returns whether a native path string is a network path (`\\server\share`), which is never served. Only Windows has them.
     */
    fun isUncPath(path: String): Boolean = false

    /**
     * Lookup key of a path (§2.3): two paths with the same key name the same file.
     * Compare paths by key, never by `Path` equality, which ignores case on Windows even inside case-sensitive folders.
     */
    fun pathKey(path: Path): String

    /**
     * Key of an API path string. A string that does not parse is its own key.
     */
    fun pathKey(apiPath: String): String = runCatching { pathKey(parse(apiPath)) }.getOrElse { apiPath }

    /**
     * Returns whether [key] is [parentKey] or inside it, compared by whole segments
     */
    fun isKeyWithin(key: String, parentKey: String): Boolean {
        val parent = segments(parentKey)
        val child = segments(key)
        return child.size >= parent.size && child.subList(0, parent.size) == parent
    }

    /**
     * Splits an API path into its non-empty segments
     */
    fun segments(apiPath: String): List<String>

    /**
     * Joins segments into an absolute API path
     */
    fun join(segments: List<String>): String

    /**
     * Checks a single file or folder name against [filenameRules]
     */
    fun validateFileName(name: String): Result<Unit> = filenameRules.validate(name)

    companion object : PathPolicy by (if (Platform.isWindows) WindowsPathPolicy else PosixPathPolicy)
}

object PosixPathPolicy : PathPolicy {
    override val root = "/"
    private val rootPath: Path = Paths.get(root)

    override val filenameRules = FilenameRules(
        invalidChars = "/\u0000",
        rejectControlChars = false,
        reservedNames = listOf(".", ".."),
        reservedNamesIgnoreExtension = false,
        rejectTrailingDotOrSpace = false,
        maxLength = 255,
        lengthUnit = FilenameRules.UTF8_BYTES,
    )

    override fun parse(apiPath: String): Path = rootPath.resolve(apiPath.trimStart('/')).normalize()

    override fun normalize(path: Path): Path =
        if (path.isAbsolute) path.normalize()
        else rootPath.resolve(path).normalize()

    override fun toApiString(path: Path) = path.toString()

    // Compares structure, not the string: on Windows this policy parses `/` to `\`
    override fun isRoot(path: Path) = path.root != null && path.nameCount == 0

    override fun isVirtualRoot(path: Path) = false

    override fun virtualRootEntries() = emptyList<Path>()

    // Every folder is case-sensitive, so the key is the path
    override fun pathKey(path: Path) = toApiString(normalize(path))

    override fun segments(apiPath: String) = apiPath.split('/').filter { it.isNotEmpty() }

    override fun join(segments: List<String>) = root + segments.joinToString("/")
}

/**
 * API paths are drive-absolute with forward slashes (`C:/Users/x`), and the root `/` is the virtual list of drives.
 *
 * Paths without a drive (`/sub/file`) parse to root-relative native paths (`\sub\file`).
 * They are only valid relative to a share root. `resolvePath` rejects them everywhere else.
 */
object WindowsPathPolicy : PathPolicy {
    override val root = "/"
    private val virtualRootPath: Path = Paths.get("\\")
    private val driveRegex = Regex("^([A-Za-z]):(.*)$")
    private val driveSegmentRegex = Regex("^[A-Za-z]:$")

    private val reservedNames = listOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$") +
        listOf("COM", "LPT").flatMap { prefix -> (0..9).map { "$prefix$it" } + listOf("¹", "²", "³").map { "$prefix$it" } }

    override val filenameRules = FilenameRules(
        invalidChars = "<>:\"/\\|?*",
        rejectControlChars = true,
        reservedNames = reservedNames,
        reservedNamesIgnoreExtension = true,
        rejectTrailingDotOrSpace = true,
        maxLength = 255,
        lengthUnit = FilenameRules.UTF16_UNITS,
    )

    override fun parse(apiPath: String): Path {
        if (apiPath.any { it.code < 32 }) throw InvalidPathException(apiPath, "Path contains control characters")
        val relative = apiPath.replace('\\', '/').trimStart('/')
        val drive = driveRegex.find(relative)

        if (drive == null) {
            checkSegments(apiPath, relative)
            return virtualRootPath.resolve(relative).normalize()
        }

        val (letter, rest) = drive.destructured
        // `C:foo` is relative to the current folder of drive C
        if (rest.isNotEmpty() && !rest.startsWith('/')) throw InvalidPathException(apiPath, "Drive-relative paths are not allowed")
        checkSegments(apiPath, rest)
        return Paths.get("${letter.uppercase()}:\\").resolve(rest.trimStart('/')).normalize()
    }

    /**
     * Rejects names that Win32 would silently change or open as a device: `name.`, `name `, `CON`, and `:` (alternate data streams)
     */
    private fun checkSegments(apiPath: String, relative: String) {
        for (segment in relative.split('/')) {
            if (segment.isEmpty() || segment == "." || segment == "..") continue
            if (segment.contains(':')) throw InvalidPathException(apiPath, "Path contains an invalid character")
            if (segment.endsWith('.') || segment.endsWith(' ')) throw InvalidPathException(apiPath, "A name cannot end with a dot or space")
            if (filenameRules.validate(segment).isNotSuccessful) throw InvalidPathException(apiPath, "Path contains an invalid name")
        }
    }

    override fun normalize(path: Path): Path {
        if (!path.isAbsolute) return parse(path.toString())
        val normalized = path.normalize()
        val root = normalized.root?.toString() ?: return normalized
        // Drive letters are case-insensitive, so they are always upper case
        if (root.length < 2 || root[1] != ':' || root[0].isUpperCase()) return normalized
        return Paths.get(root.uppercase() + normalized.toString().substring(root.length))
    }

    override fun toApiString(path: Path): String {
        if (isVirtualRoot(path)) return root
        return path.toString().replace('\\', '/')
    }

    /**
     * `Path.equals` ignores case everywhere on Windows, so a junction `a` to `A` inside a case-sensitive folder
     * would look like the same path. The keys fold case only where the folder does not distinguish it.
     */
    override fun isSamePath(a: Path, b: Path): Boolean {
        return a == b && (a.toString() == b.toString() || pathKey(a) == pathKey(b))
    }

    override fun isUncPath(path: String) = path.startsWith("\\\\")

    override fun isRoot(path: Path) = path.root != null && path.nameCount == 0

    override fun isVirtualRoot(path: Path) = !path.isAbsolute && path.root != null && path.nameCount == 0

    override fun virtualRootEntries(): List<Path> = FileSystems.getDefault().rootDirectories
        .filter { WindowsNative.isOnNetworkDrive(it) == false && Files.isDirectory(it) }
        .sortedBy { it.toString() }

    /**
     * Each name is upper-cased unless its parent folder is case-sensitive. The drive letter is always upper case.
     * Paths without a drive have no real parent folders, so every name is upper-cased.
     */
    override fun pathKey(path: Path): String {
        val normalized = normalize(path)
        if (isVirtualRoot(normalized)) return root
        if (!normalized.isAbsolute) return join(segments(toApiString(normalized)).map(::foldCase))

        val drive = normalized.root
        val keySegments = mutableListOf(drive.toString().trimEnd('\\'))
        var parent: Path = drive
        for (name in normalized) {
            val text = name.toString()
            keySegments += if (CaseSensitivity.isCaseSensitive(parent)) text else foldCase(text)
            parent = parent.resolve(name)
        }
        return join(keySegments)
    }

    /**
     * Upper case per code point (simple case mapping), like the NTFS `$UpCase` table.
     * `String.uppercase` uses full case mapping and would turn `ß` into `SS`, which NTFS does not.
     */
    fun foldCase(name: String): String = buildString(name.length) {
        name.codePoints().forEach { appendCodePoint(Character.toUpperCase(it)) }
    }

    override fun segments(apiPath: String) = apiPath.replace('\\', '/').split('/').filter { it.isNotEmpty() }

    override fun join(segments: List<String>): String {
        if (segments.isEmpty()) return root
        // Paths without a drive stay root-relative, like share paths
        if (!driveSegmentRegex.matches(segments.first())) return root + segments.joinToString("/")
        return segments.first() + "/" + segments.drop(1).joinToString("/")
    }
}

/**
 * Rules for a single file or folder name. Sent to the frontend in the app state, so both sides check the same rules.
 */
@Serializable
data class FilenameRules(
    /**
     * Characters that are never allowed
     */
    val invalidChars: String,
    /**
     * Reject characters 0x00-0x1F
     */
    val rejectControlChars: Boolean,
    val reservedNames: List<String>,
    /**
     * Reserved names also match with any extension and in any case (`con.txt` on Windows)
     */
    val reservedNamesIgnoreExtension: Boolean,
    val rejectTrailingDotOrSpace: Boolean,
    val maxLength: Int,
    /**
     * Unit of [maxLength], [UTF8_BYTES] or [UTF16_UNITS]
     */
    val lengthUnit: String,
) {
    fun validate(name: String): Result<Unit> {
        if (name.isEmpty()) return Result.reject("Name is empty.")
        if (name.any { invalidChars.contains(it) || (rejectControlChars && it.code < 32) }) {
            return Result.reject("Name contains invalid characters.")
        }
        if (isReserved(name)) return Result.reject("This name is reserved.")
        if (rejectTrailingDotOrSpace && (name.endsWith(".") || name.endsWith(" "))) {
            return Result.reject("Name cannot end with a dot or space.")
        }
        val length = if (lengthUnit == UTF8_BYTES) name.toByteArray(Charsets.UTF_8).size else name.length
        if (length > maxLength) return Result.reject("Name is too long.")
        return Result.ok(Unit)
    }

    private fun isReserved(name: String): Boolean {
        if (!reservedNamesIgnoreExtension) return reservedNames.contains(name)
        val base = name.substringBefore('.').trimEnd()
        return reservedNames.any { it.equals(name, ignoreCase = true) || it.equals(base, ignoreCase = true) }
    }

    companion object {
        const val UTF8_BYTES = "utf8Bytes"
        const val UTF16_UNITS = "utf16Units"
    }
}
