package org.filemat.server.module.file.model

import kotlinx.serialization.Serializable


@Serializable
data class FileMetadata(
    val path: String,
    val modifiedDate: Long,
    val createdDate: Long,
    val fileType: FileType,
    val size: Long,
)