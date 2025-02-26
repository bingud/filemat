package org.filemat.server.module.file.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

/**
 * @param path Path with wildcards
 * @param isExposed
 */
@Table("folder_visibility")
data class FolderVisibility(
    @Id
    @Column("path")
    val path: String,
    @Column("is_exposed")
    val isExposed: Boolean,
    @Column("created_date")
    val createdDate: Long,
)