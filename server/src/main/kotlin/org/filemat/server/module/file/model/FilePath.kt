package org.filemat.server.module.file.model

import org.filemat.server.common.util.getNormalizedPath
import org.filemat.server.config.platform.PathPolicy
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.exists

data class FilePath(
    val originalInputPath: Path,
    private val normalizedPath: Path? = null,
) {
    val path: Path by lazy { normalizedPath ?: originalInputPath.getNormalizedPath() }

    /**
     * API path string (`/a/b`, or `C:/a/b` on Windows). Stored in the database and sent to clients.
     */
    val pathString: String by lazy { PathPolicy.toApiString(path) }

    /**
     * Lookup key (`PathPolicy.pathKey`). Equal to [pathString] on Linux.
     */
    val pathKey: String by lazy { PathPolicy.pathKey(path) }

    /**
     * Last path segment. A root (`/`, `C:/`) has none, so it returns the whole path.
     */
    val filename: String get() = path.fileName?.toString() ?: pathString

    override fun toString() = pathString
    override fun equals(other: Any?): Boolean {
        return other is FilePath && this.pathString == other.pathString
    }

    override fun hashCode(): Int {
        return pathString.hashCode()
    }

    fun startsWith(other: FilePath) = this.path.startsWith(other.path)
    fun startsWith(other: Path) = this.path.startsWith(other)
    fun startsWith(other: String) = this.path.startsWith(other)

    fun exists(vararg options: LinkOption) = this.path.exists(*options)

    companion object {
        /**
         * Parses an API path string. Throws `InvalidPathException` for paths the server OS does not allow.
         */
        fun of(rawPath: String) = ofAlreadyNormalized(PathPolicy.parse(rawPath))
        fun ofAlreadyNormalized(path: Path) = FilePath(path, path)
    }
}