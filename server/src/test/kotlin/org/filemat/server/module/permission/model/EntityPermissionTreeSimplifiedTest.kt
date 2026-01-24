package org.filemat.server.module.permission.model

import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator
import org.filemat.server.common.util.unixNow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EntityPermissionTreeSimplifiedTest {

    private val tree = EntityPermissionTree()

    // Helper IDs
    private val userId = UlidCreator.getUlid()
    private val adminRole = UlidCreator.getUlid()
    private val viewerRole = UlidCreator.getUlid()

    @Test
    fun `should inherit parent permissions exactly (ignoring isInheritable flag)`() {
        // Parent has READ + MOVE. MOVE is typically "non-inheritable" in the enum, 
        // but our logic ignores that flag and inherits everything.
        tree.addPermission("/", createPerm(userId = userId, permissions = listOf(FilePermission.READ, FilePermission.MOVE)))
        
        // Child should have READ + MOVE
        val result = tree.resolveEffectivePermissions("/child/grandchild.txt", userId, emptyList())
        assertEquals(setOf(FilePermission.READ, FilePermission.MOVE), result)
    }

    @Test
    fun `should prioritize user permission over role permission at same level`() {
        // Same level: Role has WRITE, User has READ
        tree.addPermission("/target", createPerm(roleId = adminRole, permissions = listOf(FilePermission.WRITE)))
        tree.addPermission("/target", createPerm(userId = userId, permissions = listOf(FilePermission.READ)))

        // User permission (READ) should win
        val result = tree.resolveEffectivePermissions("/target", userId, listOf(adminRole))
        assertEquals(setOf(FilePermission.READ), result)
    }

    @Test
    fun `should merge multiple role permissions at same level`() {
        // Same level: Role A has READ, Role B has WRITE
        tree.addPermission("/target", createPerm(roleId = adminRole, permissions = listOf(FilePermission.WRITE)))
        tree.addPermission("/target", createPerm(roleId = viewerRole, permissions = listOf(FilePermission.READ)))

        // Result should be Union (READ + WRITE)
        val result = tree.resolveEffectivePermissions("/target", userId, listOf(adminRole, viewerRole))
        assertEquals(setOf(FilePermission.READ, FilePermission.WRITE), result)
    }

    @Test
    fun `should stop traversal at closest defined user permission (Child overrides Parent)`() {
        // Parent: WRITE
        tree.addPermission("/", createPerm(userId = userId, permissions = listOf(FilePermission.WRITE)))
        // Child: READ
        tree.addPermission("/child", createPerm(userId = userId, permissions = listOf(FilePermission.READ)))

        // Querying Child: Should get READ only (Parent's WRITE is ignored)
        val result = tree.resolveEffectivePermissions("/child", userId, emptyList())
        assertEquals(setOf(FilePermission.READ), result)
    }

    @Test
    fun `should stop traversal at closest defined role permission (Role Blocking)`() {
        // Root: Admin Role (READ, WRITE)
        tree.addPermission("/", createPerm(roleId = adminRole, permissions = listOf(FilePermission.READ, FilePermission.WRITE)))
        
        // Child: Viewer Role (READ only)
        tree.addPermission("/child", createPerm(roleId = viewerRole, permissions = listOf(FilePermission.READ)))

        // User has BOTH roles
        val roles = listOf(adminRole, viewerRole)

        // Result: The search finds permissions for 'viewerRole' at '/child'.
        // It merges all applicable roles at '/child' (only viewerRole is defined there).
        // It STOPS searching. Admin role from '/' is never seen.
        // Result is READ only.
        val result = tree.resolveEffectivePermissions("/child", userId, roles)
        assertEquals(setOf(FilePermission.READ), result)
    }

    @Test
    fun `should inherit from root if no intermediate permissions`() {
        tree.addPermission("/", createPerm(userId = userId, permissions = listOf(FilePermission.READ)))
        
        val result = tree.resolveEffectivePermissions("/a/b/c/d/e", userId, emptyList())
        assertEquals(setOf(FilePermission.READ), result)
    }

    @Test
    fun `should return empty set if no permissions found in tree`() {
        tree.addPermission("/other", createPerm(userId = userId, permissions = listOf(FilePermission.READ)))
        
        val result = tree.resolveEffectivePermissions("/target", userId, emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `should handle explicit deny (empty permissions list) correctly`() {
        // Parent: READ
        tree.addPermission("/", createPerm(userId = userId, permissions = listOf(FilePermission.READ)))
        // Child: Empty list (Explicit Deny)
        tree.addPermission("/child", createPerm(userId = userId, permissions = emptyList()))

        val result = tree.resolveEffectivePermissions("/child", userId, emptyList())
        assertTrue(result.isEmpty())
    }
    
    @Test
    fun `should handle moved nodes inheriting from new parent`() {
        // Old Parent: READ
        tree.addPermission("/oldParent", createPerm(userId = userId, permissions = listOf(FilePermission.READ)))
        // New Parent: WRITE
        tree.addPermission("/newParent", createPerm(userId = userId, permissions = listOf(FilePermission.WRITE)))
        
        // Node /oldParent/child exists
        tree.addPermission("/oldParent/child", createPerm(roleId = viewerRole, permissions = listOf(FilePermission.SHARE))) // Just creating the node

        // Move /oldParent/child -> /newParent/child
        val moveSuccess = tree.movePath("/oldParent/child", "/newParent/child")
        assertTrue(moveSuccess)
        
        // Check permissions at /newParent/child. It should now inherit WRITE from /newParent.
        // We use a different user to check inheritance (one who doesn't have the direct SHARE permission we added to create the node)
        val otherUser = UlidCreator.getUlid()
        tree.addPermission("/newParent", createPerm(userId = otherUser, permissions = listOf(FilePermission.WRITE)))
        
        val result = tree.resolveEffectivePermissions("/newParent/child", otherUser, emptyList())
        assertEquals(setOf(FilePermission.WRITE), result)
    }
    
    @Test
    fun `should combine multiple role permissions at specific level but NOT inherit roles from above`() {
        // Root: Admin (WRITE)
        tree.addPermission("/", createPerm(roleId = adminRole, permissions = listOf(FilePermission.WRITE)))
        
        // Child: Viewer (READ) AND Editor (SHARE)
        // Note: Editor is just another role ID for this test
        val editorRole = UlidCreator.getUlid()
        tree.addPermission("/child", createPerm(roleId = viewerRole, permissions = listOf(FilePermission.READ)))
        tree.addPermission("/child", createPerm(roleId = editorRole, permissions = listOf(FilePermission.SHARE)))
        
        val userRoles = listOf(adminRole, viewerRole, editorRole)
        
        // Query /child
        // Should find Viewer and Editor at /child. Merge them (READ + SHARE).
        // Should STOP there. Admin (WRITE) from root is blocked.
        val result = tree.resolveEffectivePermissions("/child", userId, userRoles)
        assertEquals(setOf(FilePermission.READ, FilePermission.SHARE), result)
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
