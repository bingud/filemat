package org.filemat.server.module.permission.service

import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.common.model.Result
import org.filemat.server.common.State
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FilesystemEntity
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.EntityPermission
import org.filemat.server.module.permission.model.FilePermission
import org.filemat.server.module.permission.model.PermissionType
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.permission.repository.PermissionRepository
import org.filemat.server.testutil.assertError
import org.filemat.server.testutil.assertRejected
import org.filemat.server.testutil.assertSuccess
import org.filemat.server.testutil.principalWith
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests who may change the permissions of a file, and that the database and the in-memory permission tree
 * (which answers every access check) never disagree after a change.
 *
 * The permission tree is real; the database, entities and file access checks are mocked.
 */
class EntityPermissionServiceTest {

    private val state = State(env = emptyMap())
    private val permissionRepository = mockk<PermissionRepository>(relaxed = true)
    private val entityService = mockk<EntityService>()
    private val fileService = mockk<FileService>()

    private val service = EntityPermissionService(
        permissionRepository = permissionRepository,
        logService = mockk<LogService>(relaxed = true),
        entityService = entityService,
        fileService = fileService,
        state = state,
    )

    private val path = FilePath.of("/data/shared/report.txt")
    private val ownerId = UlidCreator.getUlid()
    private val entity = FilesystemEntity(
        entityId = UlidCreator.getUlid(),
        path = path.pathString,
        inode = 1,
        isFilesystemSupported = true,
        ownerId = ownerId,
    )

    /** The user receiving permissions in these tests. */
    private val targetUserId = UlidCreator.getUlid()

    init {
        every { fileService.isAllowedToAccessFile(any(), any(), any()) } returns Result.ok()
        every { fileService.verifyEntityInode(any(), any()) } returns Result.ok()
        every { fileService.ensureEntityIndexed(any(), any(), any(), any()) } returns Result.ok()
        every { entityService.getByPath(any(), any()) } returns Result.ok(entity)
        every { entityService.getById(entity.entityId, any()) } returns Result.ok(entity)
    }

    private fun user(vararg systemPermissions: SystemPermission, userId: Ulid = UlidCreator.getUlid()) =
        state.principalWith(*systemPermissions, userId = userId)

    private val owner get() = user(SystemPermission.MANAGE_OWN_FILE_PERMISSIONS, userId = ownerId)
    private val manager get() = user(SystemPermission.MANAGE_ALL_FILE_PERMISSIONS)

    /** Grants [targetUserId] the given permissions on [path] as the file owner and returns the new permission. */
    private fun grant(vararg permissions: FilePermission): EntityPermission {
        val result = service.createPermission(
            user = owner,
            rawPath = path,
            targetId = targetUserId,
            mode = PermissionType.USER,
            permissions = permissions.toList(),
            existingCanonicalPath = path,
        )
        assertSuccess(result)
        return result.value
    }

    private fun effective(userId: Ulid = targetUserId) = service.getEffectivePermissions(path, userId, emptyList())

    @Nested
    inner class WhoMayEditPermissions {

        private fun createAs(user: Principal): Result<EntityPermission> = service.createPermission(
            user = user,
            rawPath = path,
            targetId = targetUserId,
            mode = PermissionType.USER,
            permissions = listOf(FilePermission.READ),
            existingCanonicalPath = path,
        )

        @Test
        fun `owner with manage-own permission may`() {
            assertSuccess(createAs(owner))
        }

        @Test
        fun `holder of manage-all permission may on a file owned by someone else`() {
            assertSuccess(createAs(manager))
        }

        @Test
        fun `owner without any manage permission may not`() {
            assertRejected(createAs(user(userId = ownerId)))
        }

        @Test
        fun `manage-own permission does not extend to files owned by someone else`() {
            assertRejected(createAs(user(SystemPermission.MANAGE_OWN_FILE_PERMISSIONS)))
        }

        @Test
        fun `user without any manage permission may not`() {
            assertRejected(createAs(user()))
        }

        @Test
        fun `rejection changes neither the database nor the tree`() {
            createAs(user())

            verify(exactly = 0) { permissionRepository.insert(any(), any(), any(), any(), any(), any(), any()) }
            assertEquals(emptySet<FilePermission>(), effective())
        }

        @Test
        fun `the same rules apply to update`() {
            val permission = grant(FilePermission.READ)

            assertRejected(service.updatePermission(user(), permission.permissionId, listOf(FilePermission.WRITE)))
            assertRejected(service.updatePermission(user(SystemPermission.MANAGE_OWN_FILE_PERMISSIONS), permission.permissionId, listOf(FilePermission.WRITE)))
            assertSuccess(service.updatePermission(manager, permission.permissionId, listOf(FilePermission.WRITE)))
        }

        @Test
        fun `the same rules apply to delete`() {
            val permission = grant(FilePermission.READ)

            assertRejected(service.deletePermission(user(), permission.permissionId))
            assertRejected(service.deletePermission(user(SystemPermission.MANAGE_OWN_FILE_PERMISSIONS), permission.permissionId))
            assertSuccess(service.deletePermission(manager, permission.permissionId))
        }
    }

    @Nested
    inner class Create {

        @Test
        fun `permission is stored and takes effect`() {
            grant(FilePermission.READ, FilePermission.WRITE)

            verify(exactly = 1) { permissionRepository.insert(any(), PermissionType.USER, entity.entityId, targetUserId, null, any(), any()) }
            assertEquals(setOf(FilePermission.READ, FilePermission.WRITE), effective())
        }

        @Test
        fun `role permission is applied to members of the role only`() {
            val roleId = UlidCreator.getUlid()
            service.createPermission(owner, path, roleId, PermissionType.ROLE, listOf(FilePermission.READ), existingCanonicalPath = path)

            assertEquals(setOf(FilePermission.READ), service.getEffectivePermissions(path, targetUserId, listOf(roleId)))
            assertEquals(emptySet<FilePermission>(), service.getEffectivePermissions(path, targetUserId, emptyList()))
        }

        @Test
        fun `second permission for the same user on the same file is rejected`() {
            grant(FilePermission.READ)

            val second = service.createPermission(owner, path, targetUserId, PermissionType.USER, listOf(FilePermission.WRITE), existingCanonicalPath = path)

            assertRejected(second)
            assertEquals(setOf(FilePermission.READ), effective())
        }

        @Test
        fun `failed database write leaves the tree untouched`() {
            every { permissionRepository.insert(any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("disk full")

            val result = service.createPermission(owner, path, targetUserId, PermissionType.USER, listOf(FilePermission.READ), existingCanonicalPath = path)

            assertError(result)
            assertEquals(emptySet<FilePermission>(), effective())
        }

        @Test
        fun `file the user may not access is rejected without changes`() {
            every { fileService.isAllowedToAccessFile(any(), any(), any()) } returns Result.reject("no access")

            val result = service.createPermission(owner, path, targetUserId, PermissionType.USER, listOf(FilePermission.READ), existingCanonicalPath = path)

            assertRejected(result)
            verify(exactly = 0) { permissionRepository.insert(any(), any(), any(), any(), any(), any(), any()) }
        }

        @Test
        fun `untracked file is indexed with the creating user as owner`() {
            val creator = manager
            val indexed = entity.copy(ownerId = creator.userId)
            every { entityService.getByPath(any(), any()) } returnsMany listOf(Result.notFound(), Result.ok(indexed))

            val result = service.createPermission(creator, path, targetUserId, PermissionType.USER, listOf(FilePermission.READ), existingCanonicalPath = path)

            assertSuccess(result)
            verify(exactly = 1) { fileService.ensureEntityIndexed(path, creator.userId, any(), any()) }
        }

        @Test
        fun `request without a user is rejected unless permissions are ignored with an explicit user`() {
            assertRejected(service.createPermission(null, path, targetUserId, PermissionType.USER, listOf(FilePermission.READ), existingCanonicalPath = path))
            assertRejected(service.createPermission(null, path, targetUserId, PermissionType.USER, listOf(FilePermission.READ), ignorePermissions = true, existingCanonicalPath = path))
            assertSuccess(service.createPermission(null, path, targetUserId, PermissionType.USER, listOf(FilePermission.READ), ignorePermissions = true, userId = ownerId, existingCanonicalPath = path))
        }
    }

    @Nested
    inner class Update {

        @Test
        fun `replaces the permission list in database and tree`() {
            val permission = grant(FilePermission.READ)

            assertSuccess(service.updatePermission(manager, permission.permissionId, listOf(FilePermission.READ, FilePermission.DELETE)))

            verify(exactly = 1) { permissionRepository.updatePermissionList(permission.permissionId, any()) }
            assertEquals(setOf(FilePermission.READ, FilePermission.DELETE), effective())
        }

        @Test
        fun `failed database write keeps the old permissions`() {
            val permission = grant(FilePermission.READ)
            every { permissionRepository.updatePermissionList(any(), any()) } throws RuntimeException("locked")

            assertError(service.updatePermission(manager, permission.permissionId, listOf(FilePermission.DELETE)))

            assertEquals(setOf(FilePermission.READ), effective())
        }

        @Test
        fun `unknown permission is rejected`() {
            assertRejected(service.updatePermission(manager, UlidCreator.getUlid(), listOf(FilePermission.READ)))
        }
    }

    @Nested
    inner class Delete {

        @Test
        fun `removes the permission from database and tree`() {
            val permission = grant(FilePermission.READ)

            assertSuccess(service.deletePermission(manager, permission.permissionId))

            verify(exactly = 1) { permissionRepository.deletePermission(permission.permissionId) }
            assertEquals(emptySet<FilePermission>(), effective())
        }

        @Test
        fun `failed database delete keeps the permission`() {
            val permission = grant(FilePermission.READ)
            every { permissionRepository.deletePermission(any()) } throws RuntimeException("locked")

            assertError(service.deletePermission(manager, permission.permissionId))

            assertEquals(setOf(FilePermission.READ), effective())
        }

        @Test
        fun `unknown permission is rejected`() {
            assertRejected(service.deletePermission(manager, UlidCreator.getUlid()))
        }
    }

    @Nested
    inner class FollowingTheFile {

        @Test
        fun `permissions move with a file that was moved`() {
            grant(FilePermission.READ)
            val newPath = FilePath.of("/data/archive/report.txt")

            service.memory_movePath(path.pathString, newPath.pathString, entity.entityId)

            assertEquals(setOf(FilePermission.READ), service.getEffectivePermissions(newPath, targetUserId, emptyList()))
            assertEquals(emptySet<FilePermission>(), effective())
        }

        @Test
        fun `permissions of a deleted file are dropped`() {
            grant(FilePermission.READ)

            service.memory_removeEntity(path.pathString, entity.entityId)

            assertEquals(emptySet<FilePermission>(), effective())
        }

        @Test
        fun `removing a user drops their permissions everywhere`() {
            grant(FilePermission.READ)

            service.removeAllPermissionsForUser(targetUserId)

            assertEquals(emptySet<FilePermission>(), effective())
            assertTrue(service.getPermittedEntities(user(userId = targetUserId)).isEmpty())
        }
    }
}
