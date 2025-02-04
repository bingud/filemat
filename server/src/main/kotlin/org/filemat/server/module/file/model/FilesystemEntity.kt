package org.filemat.server.module.file.model

import com.github.f4b6a3.ulid.Ulid
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

@Table("files")
data class FilesystemEntity(
    @Column("entity_id")
    val entityId: Ulid,
    @Column("path")
    val path: String,
    @Column("inode")
    val inode: Int?,
    @Column("is_filesystem_supported")
    val isFilesystemSupported: Boolean,
    @Column("owner_user_id")
    val ownerId: Ulid,
)
