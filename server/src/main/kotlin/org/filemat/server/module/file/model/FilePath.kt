package org.filemat.server.module.file.model

import org.filemat.server.common.util.getNormalizedPath
import org.filemat.server.common.platform.PathPolicy
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists

data class FilePath(
    val originalInputPath: Path,
    private val normalizedPath: Path? = null,
) {
    val path: Path by lazy { normalizedPath ?: originalInputPath.getNormalizedPath() }
    val pathString: String by lazy { PathPolicy.toStorageString(path) }
    val pathKey: String by lazy { PathPolicy.toPathKey(pathString)!! }

    override fun toString() = pathString
    override fun equals(other: Any?): Boolean {
        return other is FilePath && this.pathKey == other.pathKey
    }

    override fun hashCode(): Int {
        return pathKey.hashCode()
    }

    fun startsWith(other: FilePath) = PathPolicy.startsWith(this.path, other.path)
    fun startsWith(other: Path) = PathPolicy.startsWith(this.path, other)
    fun startsWith(other: String) = PathPolicy.startsWith(this.path, FilePath.of(other).path)

    fun exists(vararg options: LinkOption) = this.path.exists(*options)

    companion object {
        fun of(rawPath: String) = FilePath(Paths.get(rawPath))
        fun ofAlreadyNormalized(path: Path) = FilePath(path, path)
    }
}