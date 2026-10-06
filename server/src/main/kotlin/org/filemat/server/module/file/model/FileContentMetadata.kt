package org.filemat.server.module.file.model

import kotlinx.serialization.Serializable

@Serializable
data class FileContentMetadata(
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
) {
    fun hasAnyField(): Boolean = width != null || height != null || durationMs != null
}
