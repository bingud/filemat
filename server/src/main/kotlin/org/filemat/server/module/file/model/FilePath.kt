package org.filemat.server.module.file.model

import org.filemat.server.common.util.normalizePath
import java.nio.file.Path
import java.nio.file.Paths

data class FilePath(
    private val inputPath: String,
) {

    val originalPath = inputPath
    val path by lazy { inputPath.normalizePath() }

    val pathObject by lazy { Paths.get(path) }

    override fun toString() = path
}