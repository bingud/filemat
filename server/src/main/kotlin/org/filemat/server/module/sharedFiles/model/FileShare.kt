package org.filemat.server.module.sharedFiles.model

import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.filemat.server.config.UlidSerializer
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

@Serializable
@Table("shared_files")
data class FileShare(
    @Id
    @Column("share_id")
    val shareId: String,
    @Column("file_id")
    @Serializable(with = UlidSerializer::class)
    val fileId: Ulid,
    @Column("user_id")
    @Serializable(with = UlidSerializer::class)
    val userId: Ulid,
    @Column("created_date")
    val createdDate: Long,
    @Column("max_age")
    val maxAge: Long,
    @Column("is_password")
    val isPassword: Boolean,
    @Column("password")
    @kotlinx.serialization.Transient
    val password: String? = null,
)
fun FileShare.toPublic() = FileSharePublic(
    shareId = shareId,
    userId = userId,
    createdDate = createdDate,
    maxAge = maxAge,
    isPassword = isPassword,
)

@Serializable
data class FileSharePublic(
    val shareId: String,
    @Serializable(with = UlidSerializer::class)
    val userId: Ulid,
    val createdDate: Long,
    val maxAge: Long,
    val isPassword: Boolean,
)