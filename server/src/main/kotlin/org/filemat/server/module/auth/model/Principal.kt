package org.filemat.server.module.auth.model

import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.Serializable
import org.filemat.server.config.UlidSerializer

@Serializable
data class Principal(
    @Serializable(UlidSerializer::class)
    val userId: Ulid,
    val email: String,
    val username: String,
    val mfaTotpStatus: Boolean,
    val isBanned: Boolean
)
