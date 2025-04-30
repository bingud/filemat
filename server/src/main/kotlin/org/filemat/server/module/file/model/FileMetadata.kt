package org.filemat.server.module.file.model

import kotlinx.serialization.Serializable
import org.filemat.server.module.permission.model.FilePermission



abstract class AFileMetadata {
    abstract val path: String
    abstract val modifiedDate: Long
    abstract val createdDate: Long
    abstract val fileType: FileType
    abstract val size: Long
}

@Serializable
data class FileMetadata(
    override val path: String,
    override val modifiedDate: Long,
    override val createdDate: Long,
    override val fileType: FileType,
    override val size: Long,
) : AFileMetadata()

@Serializable
data class FullFileMetadata(
    override val path: String,
    override val modifiedDate: Long,
    override val createdDate: Long,
    override val fileType: FileType,
    override val size: Long,
    val permissions: Collection<FilePermission>
) : AFileMetadata() {
    companion object {
        fun from(m: FileMetadata, permissions: Collection<FilePermission>): FullFileMetadata {
            return FullFileMetadata(
                path = m.path,
                modifiedDate = m.modifiedDate,
                createdDate = m.createdDate,
                fileType = m.fileType,
                size = m.size,
                permissions = permissions,
            )
        }
    }
}