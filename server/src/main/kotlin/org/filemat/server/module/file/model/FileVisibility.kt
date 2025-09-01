package org.filemat.server.module.file.model

import kotlinx.serialization.Serializable
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

interface IFileVisibility {
    val path: String
    val isExposed: Boolean
}


/**
 * @param path Path with wildcards
 * @param isExposed
 */
@Table("folder_visibility")
data class FileVisibility(
    @Id
    @Column("path")
    override val path: String,
    @Column("is_exposed")
    override val isExposed: Boolean,
    @Column("created_date")
    val createdDate: Long,
) : IFileVisibility

@Table("folder_visibility")
@Serializable
data class PlainFolderVisibility(
    @Id
    @Column("path")
    override val path: String,
    @Column("is_exposed")
    override val isExposed: Boolean,
): IFileVisibility