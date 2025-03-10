package org.filemat.server.module.file.model

import com.github.f4b6a3.ulid.Ulid
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

/**
 * Representation of a file or folder in the database (with extra respective metadata)
 */
@Table("files")
data class FilesystemEntity(
    @Column("entity_id")
    val entityId: Ulid,
    @Column("path")
    val path: String?,
    @Column("inode")
    val inode: Long?,
    @Column("is_filesystem_supported")
    val isFilesystemSupported: Boolean,
    @Column("owner_user_id")
    val ownerId: Ulid,
)
