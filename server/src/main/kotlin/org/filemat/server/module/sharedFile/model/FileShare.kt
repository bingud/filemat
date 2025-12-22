package org.filemat.server.module.sharedFile.model

import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.Serializable
import org.filemat.server.common.util.unixNow
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

fun FileShare.isExpired(existingNow: Long? = null): Boolean {
    if (this.maxAge == 0L) return false
    val now = existingNow ?: unixNow()

    return this.createdDate + this.maxAge < now
}