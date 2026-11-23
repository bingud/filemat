package org.filemat.server.module.permission.model

import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator
import org.filemat.server.common.util.unixNow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

class EntityPermissionTreeManagementTest {

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
    fun `removing an entity removes all of its user and role grants`() {
        val entityId = UlidCreator.getUlid()
        val users = List(3) { UlidCreator.getUlid() }
        val roles = List(2) { UlidCreator.getUlid() }
        users.forEach { tree.addPermission("/team/report.pdf", createPerm(userId = it, permissions = listOf(FilePermission.READ)).copy(entityId = entityId)) }
        roles.forEach { tree.addPermission("/team/report.pdf", createPerm(roleId = it, permissions = listOf(FilePermission.READ)).copy(entityId = entityId)) }
        // Another entity's grant on the same node stays
        val other = createPerm(userId = userId, permissions = listOf(FilePermission.WRITE))
        tree.addPermission("/team/report.pdf", other)

        tree.removePermissionByEntityId("/team/report.pdf", entityId, null)

        users.forEach { assertTrue(tree.resolveEffectivePermissions("/team/report.pdf", it, emptyList()).isEmpty()) }
        roles.forEach { assertTrue(tree.resolveEffectivePermissions("/team/report.pdf", UlidCreator.getUlid(), listOf(it)).isEmpty()) }
        assertEquals(listOf(other), tree.getAllPermissionsForPath("/team/report.pdf"))
        assertTrue(tree.getAllAccessibleEntitiesForUser(users[0], emptyList()).isEmpty())
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
    fun `a move onto an existing node merges into it, the moved permissions winning`() {
        val otherUser = UlidCreator.getUlid()
        val moved = createPerm(userId = userId, permissions = listOf(FilePermission.READ))
        val replaced = createPerm(userId = userId, permissions = listOf(FilePermission.WRITE))
        val kept = createPerm(userId = otherUser, permissions = listOf(FilePermission.READ))
        tree.addPermission("/source", moved)
        tree.addPermission("/target", replaced)
        tree.addPermission("/target", kept)

        assertTrue(tree.movePath("/source", "/target"))

        assertEquals(moved, tree.getDirectPermissionForUser("/target", userId))
        assertEquals(kept, tree.getDirectPermissionForUser("/target", otherUser))
        assertNull(tree.getPermissionById(replaced.permissionId))
        assertTrue(tree.getAllPermissionsForPath("/source").isEmpty())
    }

    @Test
    fun `renaming a folder after its children were moved keeps the same tree as one loaded from the database`() {
        val alice = UlidCreator.getUlid()
        val bob = UlidCreator.getUlid()
        val folder = createPerm(userId = alice, permissions = listOf(FilePermission.READ))
        val contracts = createPerm(userId = bob, permissions = listOf(FilePermission.READ))
        val invoice = createPerm(roleId = roleA, permissions = listOf(FilePermission.READ))
        tree.addPermission("/projects/acme", folder)
        tree.addPermission("/projects/acme/contracts", contracts)
        tree.addPermission("/projects/acme/contracts/2026/invoice.pdf", invoice)

        // A move file by file moves the children first, then the folder
        assertTrue(tree.movePath("/projects/acme/contracts/2026/invoice.pdf", "/projects/b/contracts/2026/invoice.pdf"))
        assertTrue(tree.movePath("/projects/acme/contracts", "/projects/b/contracts"))
        assertTrue(tree.movePath("/projects/acme", "/projects/b"))

        val fromDatabase = EntityPermissionTree().apply {
            addPermission("/projects/b", folder)
            addPermission("/projects/b/contracts", contracts)
            addPermission("/projects/b/contracts/2026/invoice.pdf", invoice)
        }
        val paths = listOf(
            "/projects", "/projects/acme", "/projects/acme/new", "/projects/b", "/projects/b/contracts",
            "/projects/b/contracts/2026", "/projects/b/contracts/2026/invoice.pdf", "/projects/b/other",
        )
        for (path in paths) {
            assertEquals(fromDatabase.getAllPermissionsForPath(path).toSet(), tree.getAllPermissionsForPath(path).toSet(), path)
            for (user in listOf(alice, bob)) {
                assertEquals(
                    fromDatabase.resolveEffectivePermissions(path, user, listOf(roleA)),
                    tree.resolveEffectivePermissions(path, user, listOf(roleA)),
                    path,
                )
            }
        }
        assertEquals(
            fromDatabase.getAllAccessibleEntitiesForUser(alice, emptyList()).toSet(),
            tree.getAllAccessibleEntitiesForUser(alice, emptyList()).toSet(),
        )
        assertTrue(tree.resolveEffectivePermissions("/projects/acme", alice, emptyList()).isEmpty())
    }

    @Test
    fun `should fail to move root`() {
        // Moving root is conceptually trying to make root a child of something else
        val result = tree.movePath("/", "/subfolder")
        assertFalse(result)
    }

    @Test
    fun `should move a nested path to the root level`() {
        tree.addPermission("/a/b", createPerm(userId = userId, permissions = listOf(FilePermission.READ)))

        assertTrue(tree.movePath("/a/b", "/b"))

        assertEquals(setOf(FilePermission.READ), tree.resolveEffectivePermissions("/b/child", userId, emptyList()))
        assertTrue(tree.resolveEffectivePermissions("/a/b", userId, emptyList()).isEmpty())
    }

    @Test
    fun `should move a path under a parent that does not exist yet`() {
        tree.addPermission("/a", createPerm(userId = userId, permissions = listOf(FilePermission.READ)))

        assertTrue(tree.movePath("/a", "/x/y/a"))

        assertEquals(setOf(FilePermission.READ), tree.resolveEffectivePermissions("/x/y/a", userId, emptyList()))
        assertTrue(tree.resolveEffectivePermissions("/x/y", userId, emptyList()).isEmpty())
    }

    @Test
    fun `should ignore extra and trailing slashes in paths`() {
        val perm = createPerm(userId = userId, permissions = listOf(FilePermission.READ))
        tree.addPermission("/a//b/", perm)

        assertEquals(perm, tree.getDirectPermissionForUser("/a/b", userId))
        assertTrue(tree.movePath("/a/b/", "//c"))
        assertEquals(perm, tree.getDirectPermissionForUser("/c", userId))
    }

    @Test
    fun `should apply a permission on the root to every path`() {
        tree.addPermission("/", createPerm(userId = userId, permissions = listOf(FilePermission.READ)))

        assertEquals(setOf(FilePermission.READ), tree.resolveEffectivePermissions("/any/path", userId, emptyList()))
    }

    @Test
    // On Windows `\` is a separator
    @DisabledOnOs(OS.WINDOWS)
    fun `should treat a backslash as part of the name`() {
        tree.addPermission("/a\\b", createPerm(userId = userId, permissions = listOf(FilePermission.READ)))

        assertTrue(tree.resolveEffectivePermissions("/a/b", userId, emptyList()).isEmpty())
        assertEquals(setOf(FilePermission.READ), tree.resolveEffectivePermissions("/a\\b", userId, emptyList()))
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
