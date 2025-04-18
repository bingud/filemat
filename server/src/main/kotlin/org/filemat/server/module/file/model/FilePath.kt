package org.filemat.server.module.file.model

import org.filemat.server.common.util.getNormalizedPath
import java.nio.file.Path

data class FilePath(
    private val inputPath: String,
) {

    val originalPath: String = inputPath

    val pathObject: Path by lazy { originalPath.getNormalizedPath() }
    val path: String by lazy { pathObject.toString() }

    override fun toString() = path
    override fun equals(other: Any?): Boolean {
        return other is FilePath && this.path == other.path
    }

    override fun hashCode(): Int {
        return path.hashCode()
    }
}