package org.filemat.server.module.file.model

import kotlinx.serialization.Serializable
import org.filemat.server.module.permission.model.FilePermission

abstract class AbstractFileMetadata {
    abstract val path: String
    abstract val modifiedDate: Long
    abstract val createdDate: Long
    abstract val fileType: FileType
    abstract val size: Long
    abstract val isExecutable: Boolean
    abstract val isWritable: Boolean
}

abstract class AbstractFullFileMetadata : AbstractFileMetadata() {
    abstract val permissions: Collection<FilePermission>
    abstract val isSaved: Boolean?
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
) : AbstractFileMetadata()


@Serializable
data class FullFileMetadata(
    override val path: String,
    override val modifiedDate: Long,
    override val createdDate: Long,
    override val fileType: FileType,
    override val size: Long,
    override val isExecutable: Boolean,
    override val isWritable: Boolean,
    override val permissions: Collection<FilePermission>,
    override val isSaved: Boolean?,
) : AbstractFullFileMetadata() {
    companion object {
        fun from(m: FileMetadata, isSaved: Boolean?, permissions: Collection<FilePermission>): FullFileMetadata {
            return FullFileMetadata(
                path = m.path,
                modifiedDate = m.modifiedDate,
                createdDate = m.createdDate,
                fileType = m.fileType,
                size = m.size,
                isExecutable = m.isExecutable,
                isWritable = m.isWritable,
                permissions = permissions,
                isSaved = isSaved,
            )
        }
    }
}