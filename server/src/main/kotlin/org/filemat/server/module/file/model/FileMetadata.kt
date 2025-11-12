package org.filemat.server.module.file.model

import kotlinx.serialization.Serializable
import org.filemat.server.module.permission.model.FilePermission
import org.filemat.server.module.sharedFiles.model.FileSharePublic


abstract class AFileMetadata {
    abstract val path: String
    abstract val modifiedDate: Long
    abstract val createdDate: Long
    abstract val fileType: FileType
    abstract val size: Long
    abstract val isExecutable: Boolean
    abstract val isWritable: Boolean
}

@Serializable
data class FileMetadata(
    override val path: String,
    override val modifiedDate: Long,
    override val createdDate: Long,
    override val fileType: FileType,
    override val size: Long,
    override val isExecutable: Boolean,
    override val isWritable: Boolean,
) : AFileMetadata()

@Serializable
data class FullFileMetadata(
    override val path: String,
    override val modifiedDate: Long,
    override val createdDate: Long,
    override val fileType: FileType,
    override val size: Long,
    override val isExecutable: Boolean,
    override val isWritable: Boolean,
    val permissions: Collection<FilePermission>,
    val shares: List<FileSharePublic>
) : AFileMetadata() {
    companion object {
        fun from(m: FileMetadata, permissions: Collection<FilePermission>, shares: List<FileSharePublic>): FullFileMetadata {
            return FullFileMetadata(
                path = m.path,
                modifiedDate = m.modifiedDate,
                createdDate = m.createdDate,
                fileType = m.fileType,
                size = m.size,
                isExecutable = m.isExecutable,
                isWritable = m.isWritable,
                permissions = permissions,
                shares = shares
            )
        }
    }
}