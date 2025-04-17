package org.filemat.server.module.file.model

import org.filemat.server.common.util.getNormalizedPath

data class FilePath(
    private val inputPath: String,
) {

    val originalPath = inputPath

    val pathObject by lazy { originalPath.getNormalizedPath() }
    val path by lazy { pathObject.toString() }

    override fun toString() = path
}