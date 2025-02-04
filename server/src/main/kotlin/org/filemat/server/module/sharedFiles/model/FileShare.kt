package org.filemat.server.module.sharedFiles.model

import com.github.f4b6a3.ulid.Ulid
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

@Table("shared_files")
data class FileShare(
    @Column("share_id")
    val shareId: String,
    @Column("file_id")
    val fileId: Ulid,
    @Column("user_id")
    val userId: Ulid,
    @Column("created_date")
    val createdDate: Long,
    @Column("max_age")
    val maxAge: Long,
    @Column("is_password")
    val isPassword: Boolean,
    @Column("password")
    val password: String?,
)