package org.filemat.server.module.permission.model

import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator
import org.filemat.server.common.util.unixNow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EntityPermissionTreeEdgeCaseTest {

    private val tree = EntityPermissionTree()

    // Helper IDs
    private val userId = UlidCreator.getUlid()
    private val roleA = UlidCreator.getUlid()

    @Test
    fun `should prevent moving a folder into itself (Cycle Detection)`() {
        tree.addPermission("/parent", createPerm(userId = userId, permissions = listOf(FilePermission.READ)))
        
        // Try to move /parent into /parent/child
        val result = tree.movePath("/parent", "/parent/child")
        assertFalse(result, "Should fail to move a node into its own descendant")
    }

    @Test
    fun `should remove permission by entity ID`() {
        val perm = createPerm(userId = userId, permissions = listOf(FilePermission.READ))
        tree.addPermission("/foo", perm)
        
        tree.removePermissionByEntityId("/foo", perm.entityId, PermissionType.USER)
        
        assertTrue(tree.resolveEffectivePermissions("/foo", userId, emptyList()).isEmpty())
    }

    @Test
    fun `should find permission by ID`() {
        val perm = createPerm(userId = userId, permissions = listOf(FilePermission.READ))
        tree.addPermission("/foo", perm)

        val found = tree.getPermissionById(perm.permissionId)
        assertEquals(perm, found)
    }

    @Test
    fun `should return null when finding non-existent permission ID`() {
        val found = tree.getPermissionById(UlidCreator.getUlid())
        assertNull(found)
    }

    @Test
    fun `should remove permission by permission ID`() {
        val perm = createPerm(userId = userId, permissions = listOf(FilePermission.READ))
        tree.addPermission("/foo", perm)

        val result = tree.removePermissionByPermissionId(perm.permissionId)
        assertTrue(result)

        val found = tree.getPermissionById(perm.permissionId)
        assertNull(found)
        assertTrue(tree.resolveEffectivePermissions("/foo", userId, emptyList()).isEmpty())
    }

    @Test
    fun `should return false when removing non-existent permission ID`() {
        val result = tree.removePermissionByPermissionId(UlidCreator.getUlid())
        assertFalse(result)
    }

    @Test
    fun `should get all permissions for path`() {
        val userPerm = createPerm(userId = userId, permissions = listOf(FilePermission.READ))
        val rolePerm = createPerm(roleId = roleA, permissions = listOf(FilePermission.WRITE))

        tree.addPermission("/foo", userPerm)
        tree.addPermission("/foo", rolePerm)

        val perms = tree.getAllPermissionsForPath("/foo")
        assertEquals(2, perms.size)
        assertTrue(perms.contains(userPerm))
        assertTrue(perms.contains(rolePerm))
    }

    @Test
    fun `should get direct permission for user`() {
        val perm = createPerm(userId = userId, permissions = listOf(FilePermission.READ))
        tree.addPermission("/foo", perm)

        val found = tree.getDirectPermissionForUser("/foo", userId)
        assertEquals(perm, found)

        assertNull(tree.getDirectPermissionForUser("/bar", userId))
    }

    @Test
    fun `should get direct permission for role`() {
        val perm = createPerm(roleId = roleA, permissions = listOf(FilePermission.READ))
        tree.addPermission("/foo", perm)

        val found = tree.getDirectPermissionForRole("/foo", roleA)
        assertEquals(perm, found)
    }

    @Test
    fun `should fail to move path if source does not exist`() {
        val result = tree.movePath("/non-existent", "/target")
        assertFalse(result)
    }

    @Test
    fun `should fail to move path if target already exists`() {
        tree.addPermission("/source", createPerm(userId = userId, permissions = listOf(FilePermission.READ)))
        tree.addPermission("/target", createPerm(userId = userId, permissions = listOf(FilePermission.READ)))

        val result = tree.movePath("/source", "/target")
        assertFalse(result)
    }

    @Test
    fun `should fail to move root`() {
        // Moving root is conceptually trying to make root a child of something else
        val result = tree.movePath("/", "/subfolder")
        assertFalse(result)
    }

    // --- Helpers ---

    private fun createPerm(
        userId: Ulid? = null, 
        roleId: Ulid? = null, 
        permissions: List<FilePermission>
    ): EntityPermission {
        return EntityPermission(
            permissionId = UlidCreator.getUlid(),
            permissionType = if (userId != null) PermissionType.USER else PermissionType.ROLE,
            entityId = UlidCreator.getUlid(),
            userId = userId,
            roleId = roleId,
            permissions = permissions,
            createdDate = unixNow()
        )
    }
}
