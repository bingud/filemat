package org.filemat.server.testutil

import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator
import org.filemat.server.common.State
import org.filemat.server.common.util.unixNow
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.role.model.Role

/** A principal with the given [roles] (none by default). Roles must exist in `State.auth.roleMap` to grant anything. */
fun testPrincipal(
    userId: Ulid = UlidCreator.getUlid(),
    username: String = "user",
    roles: List<Ulid> = emptyList(),
) = Principal(
    userId = userId,
    email = "$username@test",
    username = username,
    mfaTotpStatus = false,
    mfaTotpRequired = false,
    isBanned = false,
    roles = roles.toMutableList(),
    homeFolderPath = null,
)

/** Registers a role holding [permissions] in this state and returns a principal that has it. */
fun State.principalWith(
    vararg permissions: SystemPermission,
    userId: Ulid = UlidCreator.getUlid(),
): Principal {
    val roleId = UlidCreator.getUlid()
    auth.roleMap[roleId] = Role(roleId, "role-$roleId", unixNow(), permissions.toList())
    return testPrincipal(userId = userId, roles = listOf(roleId))
}
