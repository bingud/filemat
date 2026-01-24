package org.filemat.server.module.permission.model

import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator
import org.filemat.server.common.util.unixNow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EntityPermissionTreeTest {

    private val tree = EntityPermissionTree()

    // Helper IDs
    private val userId = UlidCreator.getUlid()
    private val roleA = UlidCreator.getUlid()
    private val roleB = UlidCreator.getUlid()

    @Test
    fun `should return empty set when no permissions exist`() {
        val perms = tree.resolveEffectivePermissions("/any/path", userId, emptyList())
        assertTrue(perms.isEmpty())
    }

    @Test
    fun `should resolve direct user permission`() {
        tree.addPermission("/foo", createPerm(userId = userId, permissions = listOf(FilePermission.READ)))

        val perms = tree.resolveEffectivePermissions("/foo", userId, emptyList())
        assertEquals(setOf(FilePermission.READ), perms)
    }

    @Test
    fun `should resolve inherited user permission`() {
        // Permission at root
        tree.addPermission("/", createPerm(userId = userId, permissions = listOf(FilePermission.READ)))

        // Query deep path
        val perms = tree.resolveEffectivePermissions("/deep/path/file.txt", userId, emptyList())
        assertEquals(setOf(FilePermission.READ), perms)
    }

    @Test
    fun `should override inherited permission with specific permission`() {
        // Root: READ
        tree.addPermission("/", createPerm(userId = userId, permissions = listOf(FilePermission.READ)))
        // Specific: WRITE
        tree.addPermission("/data", createPerm(userId = userId, permissions = listOf(FilePermission.WRITE)))

        // Verify /data gets WRITE
        val dataPerms = tree.resolveEffectivePermissions("/data", userId, emptyList())
        assertEquals(setOf(FilePermission.WRITE), dataPerms)

        // Verify /other still gets READ
        val otherPerms = tree.resolveEffectivePermissions("/other", userId, emptyList())
        assertEquals(setOf(FilePermission.READ), otherPerms)
    }

    @Test
    fun `should override role permission with user permission at same level`() {
        // Role has WRITE
        tree.addPermission("/foo", createPerm(roleId = roleA, permissions = listOf(FilePermission.WRITE)))
        // User has READ (explicit restriction or different perm)
        tree.addPermission("/foo", createPerm(userId = userId, permissions = listOf(FilePermission.READ)))

        // Should return READ only (User wins)
        val perms = tree.resolveEffectivePermissions("/foo", userId, listOf(roleA))
        assertEquals(setOf(FilePermission.READ), perms)
    }

    @Test
    fun `should merge multiple role permissions at same level`() {
        // Role A: READ
        tree.addPermission("/foo", createPerm(roleId = roleA, permissions = listOf(FilePermission.READ)))
        // Role B: WRITE
        tree.addPermission("/foo", createPerm(roleId = roleB, permissions = listOf(FilePermission.WRITE)))

        // User has both roles
        val perms = tree.resolveEffectivePermissions("/foo", userId, listOf(roleA, roleB))
        
        // Should have BOTH
        assertEquals(setOf(FilePermission.READ, FilePermission.WRITE), perms)
    }

    @Test
    fun `should not merge inherited role permissions with specific ones`() {
        // Root Role A: READ
        tree.addPermission("/", createPerm(roleId = roleA, permissions = listOf(FilePermission.READ)))
        
        // Specific Role B: WRITE at /data
        tree.addPermission("/data", createPerm(roleId = roleB, permissions = listOf(FilePermission.WRITE)))

        // User has both roles, querying /data
        val perms = tree.resolveEffectivePermissions("/data", userId, listOf(roleA, roleB))

        // Should ONLY have WRITE (Specific level wins, stopping traversal)
        assertEquals(setOf(FilePermission.WRITE), perms)
    }

    @Test
    fun `should handle complex inheritance scenario`() {
        // /        -> Role A (READ)
        // /secret  -> Role B (WRITE)
        // /secret/restricted -> User (READ only, override Role B)

        tree.addPermission("/", createPerm(roleId = roleA, permissions = listOf(FilePermission.READ)))
        tree.addPermission("/secret", createPerm(roleId = roleB, permissions = listOf(FilePermission.WRITE)))
        tree.addPermission("/secret/restricted", createPerm(userId = userId, permissions = listOf(FilePermission.READ)))

        val roles = listOf(roleA, roleB)

        // 1. Check root file
        assertEquals(setOf(FilePermission.READ), tree.resolveEffectivePermissions("/file.txt", userId, roles))

        // 2. Check secret file (Role B overrides Role A)
        assertEquals(setOf(FilePermission.WRITE), tree.resolveEffectivePermissions("/secret/file.txt", userId, roles))

        // 3. Check restricted file (User override Role B)
        assertEquals(setOf(FilePermission.READ), tree.resolveEffectivePermissions("/secret/restricted/file.txt", userId, roles))
    }

    @Test
    fun `should block inheritance if a role is defined but has empty permissions (Explicit Deny)`() {
        // Root: Role A -> READ
        tree.addPermission("/", createPerm(roleId = roleA, permissions = listOf(FilePermission.READ)))
        
        // Child: Role A -> Empty (Explicit Deny)
        tree.addPermission("/child", createPerm(roleId = roleA, permissions = emptyList()))

        // User has Role A
        // Should find Role A at /child (empty) and STOP, returning empty set.
        val perms = tree.resolveEffectivePermissions("/child", userId, listOf(roleA))
        assertTrue(perms.isEmpty(), "Should be denied by explicit empty permission at child level")
    }

    @Test
    fun `should prevent moving a folder into itself (Cycle Detection)`() {
        tree.addPermission("/parent", createPerm(userId = userId, permissions = listOf(FilePermission.READ)))
        
        // Try to move /parent into /parent/child
        val result = tree.movePath("/parent", "/parent/child")
        org.junit.jupiter.api.Assertions.assertFalse(result, "Should fail to move a node into its own descendant")
    }

    @Test
    fun `should move path and preserve permissions`() {
        tree.addPermission("/source/doc", createPerm(userId = userId, permissions = listOf(FilePermission.READ)))
        
        val success = tree.movePath("/source/doc", "/dest/doc")
        assertTrue(success)

        // Verify old path is empty
        assertTrue(tree.resolveEffectivePermissions("/source/doc", userId, emptyList()).isEmpty())
        
        // Verify new path has permission
        assertEquals(setOf(FilePermission.READ), tree.resolveEffectivePermissions("/dest/doc", userId, emptyList()))
    }

    @Test
    fun `should identify top-level accessible entities with gaps`() {
        // Setup:
        // /        (No Access)
        // /A       (READ) -> Top Level
        // /A/Sub   (READ) -> Not Top Level (Covered by A)
        // /B       (No Access)
        // /B/Sub   (READ) -> Top Level (Parent B has no access)
        
        tree.addPermission("/A", createPerm(userId = userId, permissions = listOf(FilePermission.READ)))
        tree.addPermission("/A/Sub", createPerm(userId = userId, permissions = listOf(FilePermission.READ)))
        tree.addPermission("/B/Sub", createPerm(userId = userId, permissions = listOf(FilePermission.READ)))

        val accessible = tree.getAllAccessibleEntitiesForUser(userId, emptyList())
        
        // We expect 2 permissions: one for /A and one for /B/Sub
        assertEquals(2, accessible.size)
    }

    @Test
    fun `should remove permission by entity ID`() {
        val perm = createPerm(userId = userId, permissions = listOf(FilePermission.READ))
        tree.addPermission("/foo", perm)
        
        tree.removePermissionByEntityId("/foo", perm.entityId, PermissionType.USER)
        
        assertTrue(tree.resolveEffectivePermissions("/foo", userId, emptyList()).isEmpty())
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
