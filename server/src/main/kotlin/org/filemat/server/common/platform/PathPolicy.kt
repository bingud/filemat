package org.filemat.server.common.platform

import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import java.nio.file.Path
import java.nio.file.Paths
import java.text.Normalizer
import java.util.Locale

object PathPolicy {
    private val reservedWindowsNames = setOf(
        "CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    fun normalizeInputPath(input: String): Path {
        val normalizedSeparators = if (Platform.isWindows) input.replace('\\', '/') else input
        val path = Paths.get(normalizedSeparators)
        return if (path.isAbsolute) {
            path.normalize()
        } else {
            Paths.get("/").resolve(path).normalize()
        }
    }

    fun normalizePath(path: Path): Path {
        return if (path.isAbsolute) path.normalize() else Paths.get("/").resolve(path).normalize()
    }

    fun toStorageString(path: Path): String {
        val normalized = path.normalize().toString()
        return if (Platform.isWindows) normalized.replace('\\', '/') else normalized
    }

    fun toPathKey(path: String?): String? {
        path ?: return null
        val normalized = Normalizer.normalize(path.replace('\\', '/'), Normalizer.Form.NFC)
        return if (Platform.isWindows) normalized.lowercase(Locale.ROOT) else normalized
    }

    fun startsWith(path: Path, parent: Path): Boolean {
        val childKey = toPathKey(toStorageString(path)) ?: return false
        val parentKey = toPathKey(toStorageString(parent)) ?: return false
        if (childKey == parentKey) return true
        val parentWithSeparator = if (parentKey.endsWith("/")) parentKey else "$parentKey/"
        return childKey.startsWith(parentWithSeparator)
    }

    fun resolveContainedRelative(base: Path, relativeInput: String): Result<Path> {
        val relative = validateRelativeApiPath(relativeInput).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }
        val resolved = base.resolve(relative).normalize()
        return if (startsWith(resolved, base.normalize())) Result.ok(resolved) else Result.reject("Path escapes shared folder.")
    }

    fun isRoot(path: Path): Boolean {
        val normalized = path.normalize()
        return normalized.parent == null || normalized == normalized.root
    }

    fun validateFileName(name: String): Result<Unit> {
        if (!Platform.isWindows) return Result.ok()
        if (name.isBlank()) return Result.reject("Filename must not be blank.")
        if (name == "." || name == "..") return Result.reject("Filename is reserved.")
        if (name.endsWith(" ") || name.endsWith(".")) {
            return Result.reject("Windows filenames must not end with a space or dot.")
        }

        val invalid = name.firstOrNull { it.code in 0..31 || it in setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*') }
        if (invalid != null) return Result.reject("Filename contains an invalid Windows character: $invalid")

        val baseName = name.substringBeforeLast('.').uppercase(Locale.ROOT)
        if (baseName in reservedWindowsNames) return Result.reject("Filename uses a reserved Windows device name.")

        return Result.ok()
    }

    fun validateRelativeApiPath(path: String): Result<String> {
        val normalized = path.replace('\\', '/')
        if (normalized.startsWith("/") || normalized.startsWith("//")) {
            return Result.reject("Shared paths must be relative.")
        }
        if (Platform.isWindows) {
            if (Regex("^[a-zA-Z]:").containsMatchIn(normalized)) return Result.reject("Drive paths are not allowed here.")
            if (normalized.startsWith("\\\\") || normalized.startsWith("//")) return Result.reject("UNC paths are not allowed here.")
        }
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." }) return Result.reject("Path traversal is not allowed.")
        segments.forEach { segment ->
            validateFileName(segment).let { if (it.isNotSuccessful) return it.cast() }
        }
        return Result.ok(segments.joinToString("/"))
    }
}
