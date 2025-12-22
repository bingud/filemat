package org.filemat.server.module.savedFile

import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.filemat.server.config.UlidSerializer
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

@Serializable
@Table("saved_files")
data class SavedFile(
    @Serializable(with = UlidSerializer::class)
    @Column("user_id")
    val userId: Ulid,
    @Column("path")
    val path: String,
    @Column("created_date")
    val createdDate: Long,
)

fun SavedFile.serialize() = Json.encodeToString(this)