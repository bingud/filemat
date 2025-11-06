package org.filemat.server.common.util.dto

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.user.model.UserAction

data class RequestMeta(
    val initiatorId: Ulid? = null,
    val targetId: Ulid? = null,
    val ip: String? = null,
    var action: UserAction,
    val principal: Principal? = null,
) {
    val targetUserId: Ulid
        get() = targetId!!
}