package org.filemat.server.module.file.service.file.component

import com.github.f4b6a3.ulid.UlidCreator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FilesystemEntity
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.file.service.FileVisibilityService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.permission.service.EntityPermissionService
import org.filemat.server.module.user.model.UserAction
import org.filemat.server.testutil.assertRejected
import org.filemat.server.testutil.assertSuccess
import org.junit.jupiter.api.Test

/**
 * Tests how [FileSecurityService] keeps database entities in sync with the real files:
 * a file's inode identifies it, so entities must follow files that were moved or replaced outside the app.
 */
class FileSecurityServiceEntityRepairTest {

    private val path = FilePath.of("/data/report.txt")
    private val action = UserAction.READ_FOLDER

    private val entityService = mockk<EntityService>(relaxed = true)
    private val filesystemService = mockk<FilesystemService>()

    private val service = FileSecurityService(
        fileVisibilityService = mockk<FileVisibilityService>(),
        entityPermissionService = mockk<EntityPermissionService>(),
        entityService = entityService,
        filesystemService = filesystemService,
        state = State(env = emptyMap()),
    )

    init {
        every { entityService.withEntityRepairLock(any<() -> Result<Unit>>()) } answers { firstArg<() -> Result<Unit>>()() }
        every { entityService.getByPath(any(), any()) } returns Result.notFound()
        every { entityService.getByInode(any(), any()) } returns Result.notFound()
        every { entityService.updateInode(any(), any(), any(), any()) } returns Result.ok()
        every { entityService.updatePath(any(), any(), any(), any(), any()) } returns Result.ok()
        every { entityService.move(any(), any(), any()) } returns Result.ok()
    }

    private fun entity(path: String?, inode: Long?, supported: Boolean = true) = FilesystemEntity(
        entityId = UlidCreator.getUlid(),
        path = path,
        inode = inode,
        isFilesystemSupported = supported,
        ownerId = null,
    )

    private fun fileOnDiskHasInode(inode: Long?) {
        every { filesystemService.getInode(any(), any()) } returns inode
    }

    @Test
    fun `entity with a matching inode is left untouched`() {
        every { entityService.getByPath(any(), any()) } returns Result.ok(entity(path.pathString, inode = 1))
        fileOnDiskHasInode(1)

        assertSuccess(service.verifyEntityInode(path, action))

        verify(exactly = 0) { entityService.updateInode(any(), any(), any(), any()) }
        verify(exactly = 0) { entityService.updatePath(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { entityService.move(any(), any(), any()) }
    }

    @Test
    fun `file replaced at the same path takes over the existing entity with its new inode`() {
        val stale = entity(path.pathString, inode = 1)
        every { entityService.getByPath(any(), any()) } returns Result.ok(stale)
        fileOnDiskHasInode(2)

        assertSuccess(service.verifyEntityInode(path, action))

        verify(exactly = 1) { entityService.updateInode(stale.entityId, 2, stale, action) }
    }

    @Test
    fun `entity follows its file when the file was moved outside the app`() {
        val moved = entity("/old/location.txt", inode = 5)
        every { entityService.getByInode(5, any()) } returns Result.ok(moved)
        fileOnDiskHasInode(5)

        assertSuccess(service.verifyEntityInode(path, action))

        verify(exactly = 1) { entityService.updatePath(moved.entityId, path.pathString, moved, action) }
    }

    @Test
    fun `entity loses its path when the file is gone`() {
        every { entityService.getByPath(any(), any()) } returns Result.ok(entity(path.pathString, inode = 1))
        fileOnDiskHasInode(null)

        assertSuccess(service.verifyEntityInode(path, action))

        verify(exactly = 1) { entityService.move(path, null, action) }
    }

    @Test
    fun `entity on an unsupported filesystem is only checked for existence`() {
        every { entityService.getByPath(any(), any()) } returns Result.ok(entity(path.pathString, inode = null, supported = false))
        every { filesystemService.exists(any(), any()) } returns false

        assertRejected(service.verifyEntityInode(path, action))

        verify(exactly = 0) { filesystemService.getInode(any(), any()) }
    }

    @Test
    fun `indexing creates an entity for an untracked file`() {
        val ownerId = UlidCreator.getUlid()
        fileOnDiskHasInode(7)
        every { entityService.create(any(), any(), any()) } returns Result.ok(entity(path.pathString, inode = 7))

        assertSuccess(service.ensureEntityIndexed(path, ownerId, action))

        verify(exactly = 1) { entityService.create(path, ownerId, action) }
    }

    @Test
    fun `indexing does not create a second entity for an already tracked file`() {
        every { entityService.getByPath(any(), any()) } returns Result.ok(entity(path.pathString, inode = 7))
        fileOnDiskHasInode(7)

        assertSuccess(service.ensureEntityIndexed(path, UlidCreator.getUlid(), action))

        verify(exactly = 0) { entityService.create(any(), any(), any()) }
    }
}
