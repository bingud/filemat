package org.filemat.server.module.file.model

import kotlinx.serialization.Serializable


@Serializable
data class FileMetadata(
    val filename: String,
    val modificationTime: Long,
    val creationTime: Long,
    val fileType: FileType,
    val size: Long,
)