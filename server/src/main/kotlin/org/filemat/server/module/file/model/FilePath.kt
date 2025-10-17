package org.filemat.server.module.file.model

import org.filemat.server.common.util.getNormalizedPath
import java.nio.file.Path
import java.nio.file.Paths

data class FilePath(
    val originalInputPath: Path,
    private val normalizedPath: Path? = null,
) {
    val path: Path by lazy { normalizedPath ?: originalInputPath.getNormalizedPath() }
    val pathString: String by lazy { path.toString() }

    override fun toString() = pathString
    override fun equals(other: Any?): Boolean {
        return other is FilePath && this.pathString == other.pathString
    }

    override fun hashCode(): Int {
        return pathString.hashCode()
    }

    fun startsWith(other: FilePath) = this.path.startsWith(other.path)

    companion object {
        fun of(rawPath: String) = FilePath(Paths.get(rawPath))
        fun ofAlreadyNormalized(path: Path) = FilePath(path, path)
    }
}