package org.filemat.server.module.permission.model

import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.Serializable
import org.filemat.server.config.UlidSerializer
import org.filemat.server.module.user.model.MiniUser


@Serializable
data class EntityPermissionMeta(
    @Serializable(UlidSerializer::class)
    val ownerId: Ulid?,
    val permissions: List<EntityPermission>,
    /** Permissions follow the path only, not the file when it's moved outside Filemat (FAT, exFAT, network volumes and hard links) */
    val isPathOnly: Boolean = false,
    var miniUserList: List<MiniUser>? = null,
)
