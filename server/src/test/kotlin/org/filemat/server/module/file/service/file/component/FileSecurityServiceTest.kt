package org.filemat.server.module.file.service.file.component

import com.github.f4b6a3.ulid.UlidCreator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.unixNow
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FilesystemEntity
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.file.service.FileVisibilityService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.permission.model.FilePermission
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.permission.service.EntityPermissionService
import org.filemat.server.module.role.model.Role
import org.filemat.server.testutil.assertError
import org.filemat.server.testutil.assertRejected
import org.filemat.server.testutil.assertSuccess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Tests the authorization decisions of [FileSecurityService].
 *
 * Collaborators are mocked so each test states only what matters: who the user is,
 * which file permissions the permission tree grants them, and whether the path is blocked.
 */
class FileSecurityServiceTest {

    /** Every guarded operation and the file permission it requires. */
    enum class Operation(
        val required: FilePermission,
        val check: (FileSecurityService, Principal, FilePath, Boolean?) -> Result<Unit>,
    ) {
        EDIT(FilePermission.WRITE, { s, u, p, ignore -> s.isAllowedToEditFile(u, p, ignore) }),
        SHARE(FilePermission.SHARE, { s, u, p, ignore -> s.isAllowedToShareFile(u, p, ignore) }),
        DELETE(FilePermission.DELETE, { s, u, p, ignore -> s.isAllowedToDeleteFile(u, p, ignore) }),
        MOVE(FilePermission.MOVE, { s, u, p, ignore -> s.isAllowedToMoveFile(u, p, ignore) }),
        RENAME(FilePermission.RENAME, { s, u, p, ignore -> s.isAllowedToRenameFile(u, p, ignore) }),
    }

    private val file = FilePath.of("/data/report.txt")
    private val fileInode = 42L

    private val state = State(env = emptyMap())
    private val fileVisibilityService = mockk<FileVisibilityService>()
    private val entityPermissionService = mockk<EntityPermissionService>()
    private val entityService = mockk<EntityService>()
    private val filesystemService = mockk<FilesystemService>()

    private val service = serviceWith(state)

    init {
        allowAllPaths()
        every { entityPermissionService.getEffectivePermissions(any(), any(), any()) } returns emptySet()
        // The file exists and its stored inode is up to date, so no repair is needed.
        every { entityService.getByPath(any(), any()) } returns Result.ok(entity(file))
        every { filesystemService.getInode(any(), any()) } returns fileInode
    }

    // region Fixtures

    private fun serviceWith(customState: State) = FileSecurityService(
        fileVisibilityService = fileVisibilityService,
        entityPermissionService = entityPermissionService,
        entityService = entityService,
        filesystemService = filesystemService,
        state = customState,
    )

    private fun allowAllPaths() {
        every { fileVisibilityService.isPathAllowed(any()) } returns null
    }

    private fun blockAllPaths() {
        every { fileVisibilityService.isPathAllowed(any()) } returns "blocked"
    }

    private fun principal(vararg systemPermissions: SystemPermission): Principal {
        val roleId = UlidCreator.getUlid()
        state.auth.roleMap[roleId] = Role(roleId, "role-$roleId", unixNow(), systemPermissions.toList())
        return Principal(
            userId = UlidCreator.getUlid(),
            email = "user@test",
            username = "user",
            mfaTotpStatus = false,
            mfaTotpRequired = false,
            isBanned = false,
            roles = mutableListOf(roleId),
            homeFolderPath = null,
        )
    }

    private fun superAdmin() = principal(SystemPermission.SUPER_ADMIN)

    /** A user without system permissions, granted [filePermissions] on every path by the permission tree. */
    private fun regularUser(vararg filePermissions: FilePermission): Principal {
        val user = principal()
        every {
            entityPermissionService.getEffectivePermissions(any(), user.userId, any())
        } returns filePermissions.toSet()
        return user
    }

    private fun entity(path: FilePath) = FilesystemEntity(
        entityId = UlidCreator.getUlid(),
        path = path.pathString,
        inode = fileInode,
        isFilesystemSupported = true,
        ownerId = null,
    )

    // endregion

    @Nested
    inner class Reading {

        @Test
        fun `anonymous user is rejected`() {
            assertRejected(service.isAllowedToAccessFile(user = null, canonicalPath = file))
        }

        @Test
        fun `anonymous user is allowed when permissions are ignored for a share link`() {
            assertSuccess(service.isAllowedToAccessFile(user = null, canonicalPath = file, ignorePermissions = true))
        }

        @Test
        fun `blocked path is rejected even when permissions are ignored`() {
            blockAllPaths()

            assertRejected(service.isAllowedToAccessFile(user = null, canonicalPath = file, ignorePermissions = true))
        }

        @Test
        fun `blocked path is rejected for a super admin`() {
            blockAllPaths()

            assertRejected(service.isAllowedToAccessFile(superAdmin(), file))
        }

        @Test
        fun `super admin can read without any file grant`() {
            assertSuccess(service.isAllowedToAccessFile(superAdmin(), file))
        }

        @Test
        fun `user with the read permission is allowed`() {
            assertSuccess(service.isAllowedToAccessFile(regularUser(FilePermission.READ), file))
        }

        @Test
        fun `user without the read permission is rejected`() {
            assertRejected(service.isAllowedToAccessFile(regularUser(), file))
        }

        @Test
        fun `user with every permission except read cannot read`() {
            val allButRead = FilePermission.entries.filter { it != FilePermission.READ }.toTypedArray()

            assertRejected(service.isAllowedToAccessFile(regularUser(*allButRead), file))
        }

        @Test
        fun `failure while verifying the file fails closed even if the user has read`() {
            every { entityService.getByPath(any(), any()) } returns Result.error("database unavailable")

            assertError(service.isAllowedToAccessFile(regularUser(FilePermission.READ), file))
        }
    }

    @Nested
    inner class Operations {

        @ParameterizedTest(name = "{0}")
        @EnumSource(Operation::class)
        fun `allowed with read plus the required permission`(op: Operation) {
            val user = regularUser(FilePermission.READ, op.required)

            assertSuccess(op.check(service, user, file, null))
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Operation::class)
        fun `rejected with read only`(op: Operation) {
            val user = regularUser(FilePermission.READ)

            assertRejected(op.check(service, user, file, null))
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Operation::class)
        fun `rejected when every other permission is granted`(op: Operation) {
            val everythingElse = FilePermission.entries.filter { it != op.required }.toTypedArray()
            val user = regularUser(*everythingElse)

            assertRejected(op.check(service, user, file, null))
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Operation::class)
        fun `rejected without read even when the required permission is granted`(op: Operation) {
            val user = regularUser(op.required)

            assertRejected(op.check(service, user, file, null))
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Operation::class)
        fun `super admin is allowed without any file grant`(op: Operation) {
            assertSuccess(op.check(service, superAdmin(), file, null))
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Operation::class)
        fun `access-all-files holder is allowed without any file grant`(op: Operation) {
            assertSuccess(op.check(service, principal(SystemPermission.ACCESS_ALL_FILES), file, null))
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Operation::class)
        fun `forcing permission checks makes even a super admin need the grant`(op: Operation) {
            assertRejected(op.check(service, superAdmin(), file, false))
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Operation::class)
        fun `blocked path is rejected even for a super admin`(op: Operation) {
            blockAllPaths()

            assertRejected(op.check(service, superAdmin(), file, null))
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Operation::class)
        fun `ignoring permissions allows the operation without any grant`(op: Operation) {
            assertSuccess(op.check(service, regularUser(), file, true))
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Operation::class)
        fun `ignoring permissions still rejects a blocked path`(op: Operation) {
            blockAllPaths()

            assertRejected(op.check(service, regularUser(), file, true))
        }
    }

    @Nested
    inner class DataFolder {
        private val dataFile = FilePath.of("/var/lib/filemat/database.sqlite")

        @ParameterizedTest(name = "{0}")
        @EnumSource(Operation::class, names = ["EDIT", "MOVE", "RENAME", "DELETE"])
        fun `cannot be modified by default, even by a super admin`(op: Operation) {
            assertRejected(op.check(service, superAdmin(), dataFile, null))
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Operation::class, names = ["EDIT", "MOVE", "RENAME", "DELETE"])
        fun `can be modified when the environment allows writing`(op: Operation) {
            val writableState = State(env = mapOf("FM_ALLOW_WRITE_DATA_FOLDER" to "true"))
            val admin = superAdmin()
            writableState.auth.roleMap.putAll(state.auth.roleMap)
            every { entityService.getByPath(any(), any()) } returns Result.ok(entity(dataFile))

            assertSuccess(op.check(serviceWith(writableState), admin, dataFile, null))
        }

        @Test
        fun `sibling folder with a similar name is not treated as the data folder`() {
            val sibling = FilePath.of("/var/lib/filemat-backup/file.txt")
            every { entityService.getByPath(any(), any()) } returns Result.ok(entity(sibling))

            assertSuccess(service.isAllowedToEditFile(superAdmin(), sibling))
        }
    }

    @Nested
    inner class SystemFolders {

        @Test
        fun `system folder cannot be deleted`() {
            assertRejected(service.isPathDeletable(FilePath.of("/etc")))
        }

        @Test
        fun `system folder cannot be deleted through a non-normalized path`() {
            assertRejected(service.isPathDeletable(FilePath.of("/etc/../etc/")))
        }

        @Test
        fun `content inside a system folder can be deleted`() {
            assertSuccess(service.isPathDeletable(FilePath.of("/etc/app.conf")))
        }

        @Test
        fun `ordinary folder can be deleted`() {
            assertSuccess(service.isPathDeletable(file))
        }

        @Test
        fun `system folder marked force-deletable can be deleted`() {
            val forcedState = State(env = mapOf("FM_FORCE_DELETABLE_FOLDERS" to "/etc"))

            assertSuccess(serviceWith(forcedState).isPathDeletable(FilePath.of("/etc")))
        }

        @Test
        fun `deleting a system folder is rejected even for a super admin`() {
            val etc = FilePath.of("/etc")
            every { entityService.getByPath(any(), any()) } returns Result.ok(entity(etc))

            assertRejected(service.isAllowedToDeleteFile(superAdmin(), etc))
        }
    }

    @Nested
    inner class PermissionLookup {

        @Test
        fun `super admin has every file permission`() {
            assertEquals(FilePermission.entries.toSet(), service.getActualFilePermissions(superAdmin(), file))
        }

        @Test
        fun `access-all-files holder has every file permission`() {
            val user = principal(SystemPermission.ACCESS_ALL_FILES)

            assertEquals(FilePermission.entries.toSet(), service.getActualFilePermissions(user, file))
        }

        @Test
        fun `other system permissions grant no file permissions`() {
            val user = principal(SystemPermission.MANAGE_USERS, SystemPermission.MANAGE_ALL_FILE_PERMISSIONS)

            assertEquals(emptySet<FilePermission>(), service.getActualFilePermissions(user, file))
        }

        @Test
        fun `regular user has exactly the permissions granted by the tree`() {
            val user = regularUser(FilePermission.READ, FilePermission.WRITE)

            assertEquals(setOf(FilePermission.READ, FilePermission.WRITE), service.getActualFilePermissions(user, file))
        }

        @Test
        fun `hasFilePermission is true only for granted permissions`() {
            val user = regularUser(FilePermission.SHARE)

            assertTrue(service.hasFilePermission(file, user, permission = FilePermission.SHARE))
            assertFalse(service.hasFilePermission(file, user, permission = FilePermission.DELETE))
        }

        @Test
        fun `hasAdminAccess requires an admin system permission`() {
            assertTrue(service.hasAdminAccess(superAdmin()))
            assertTrue(service.hasAdminAccess(principal(SystemPermission.ACCESS_ALL_FILES)))
            assertFalse(service.hasAdminAccess(principal(SystemPermission.MANAGE_USERS)))
            assertFalse(service.hasAdminAccess(regularUser(*FilePermission.entries.toTypedArray())))
        }

        @Test
        fun `admin read access does not consult the permission tree`() {
            service.isAllowedToAccessFile(superAdmin(), file)

            verify(exactly = 0) { entityPermissionService.getEffectivePermissions(any(), any(), any()) }
        }
    }
}
