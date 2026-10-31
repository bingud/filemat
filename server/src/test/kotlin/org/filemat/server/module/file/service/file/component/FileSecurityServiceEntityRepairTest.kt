package org.filemat.server.module.file.service.file.component

import com.github.f4b6a3.ulid.UlidCreator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.config.platform.PathPolicy
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
 * a file's key identifies it, so entities must follow files that were moved or replaced outside the app.
 */
class FileSecurityServiceEntityRepairTest {

    private val path = FilePath.of("/data/report.txt")
    private val otherPath = FilePath.of("/data/link.txt")
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
        every { entityService.getByFileKey(any(), any()) } returns Result.notFound()
        every { entityService.updateFileKey(any(), any(), any(), any(), any()) } returns Result.ok()
        every { entityService.updatePath(any(), any(), any(), any(), any(), any()) } returns Result.ok()
        every { entityService.move(any(), any(), any()) } returns Result.ok()
        every { filesystemService.getFileKey(any()) } returns null
    }

    private fun entity(path: String?, fileKey: String?, supported: Boolean = true) = FilesystemEntity(
        entityId = UlidCreator.getUlid(),
        path = path,
        fileKey = fileKey,
        isFilesystemSupported = supported,
        ownerId = null,
        pathKey = path?.let { PathPolicy.pathKey(it) },
    )

    private fun fileOnDiskHasKey(fileKey: String?) {
        every { filesystemService.getFileKey(path.path) } returns fileKey
    }

    @Test
    fun `entity with a matching file key is left untouched`() {
        every { entityService.getByPath(any(), any()) } returns Result.ok(entity(path.pathString, fileKey = "k1"))
        fileOnDiskHasKey("k1")

        assertSuccess(service.verifyEntityIdentity(path, action))

        verify(exactly = 0) { entityService.updateFileKey(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { entityService.updatePath(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { entityService.move(any(), any(), any()) }
    }

    @Test
    fun `file replaced at the same path takes over the existing entity with its new key`() {
        val stale = entity(path.pathString, fileKey = "k1")
        every { entityService.getByPath(any(), any()) } returns Result.ok(stale)
        fileOnDiskHasKey("k2")

        assertSuccess(service.verifyEntityIdentity(path, action))

        verify(exactly = 1) { entityService.updateFileKey(stale.entityId, "k2", stale, action, null) }
    }

    @Test
    fun `entity follows its file when the file was moved outside the app`() {
        val moved = entity("/old/location.txt", fileKey = "k5")
        every { entityService.getByFileKey("k5", any()) } returns Result.ok(moved)
        fileOnDiskHasKey("k5")

        assertSuccess(service.verifyEntityIdentity(path, action))

        verify(exactly = 1) { entityService.updatePath(moved.entityId, path.pathString, moved, action, any(), path.pathKey) }
    }

    @Test
    fun `a hard link does not take the entity of the first path`() {
        val first = entity(otherPath.pathString, fileKey = "k5")
        every { entityService.getByFileKey("k5", any()) } returns Result.ok(first)
        every { filesystemService.getFileKey(otherPath.path) } returns "k5"
        fileOnDiskHasKey("k5")

        assertSuccess(service.verifyEntityIdentity(path, action))

        verify(exactly = 0) { entityService.updatePath(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { entityService.updateFileKey(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a path entity that became a hard link follows its path only`() {
        val pathEntity = entity(path.pathString, fileKey = "k1")
        every { entityService.getByPath(any(), any()) } returns Result.ok(pathEntity)
        every { entityService.getByFileKey("k5", any()) } returns Result.ok(entity(otherPath.pathString, fileKey = "k5"))
        every { filesystemService.getFileKey(otherPath.path) } returns "k5"
        fileOnDiskHasKey("k5")

        assertSuccess(service.verifyEntityIdentity(path, action))

        verify(exactly = 1) { entityService.updateFileKey(pathEntity.entityId, null, pathEntity, action, false) }
    }

    @Test
    fun `entity loses its path when the file is gone`() {
        every { entityService.getByPath(any(), any()) } returns Result.ok(entity(path.pathString, fileKey = "k1"))
        fileOnDiskHasKey(null)

        assertSuccess(service.verifyEntityIdentity(path, action))

        verify(exactly = 1) { entityService.move(path, null, action) }
    }

    @Test
    fun `entity on an unsupported filesystem is only checked for existence`() {
        every { entityService.getByPath(any(), any()) } returns Result.ok(entity(path.pathString, fileKey = null, supported = false))
        every { filesystemService.exists(any(), any()) } returns false

        assertRejected(service.verifyEntityIdentity(path, action))

        verify(exactly = 0) { filesystemService.getFileKey(any()) }
    }

    @Test
    fun `entity that had no key when keys were added gets one when its file is back`() {
        val entity = entity(path.pathString, fileKey = null)
        every { entityService.getByPath(any(), any()) } returns Result.ok(entity)
        fileOnDiskHasKey("k3")

        assertSuccess(service.verifyEntityIdentity(path, action))

        verify(exactly = 1) { entityService.updateFileKey(entity.entityId, "k3", entity, action, null) }
    }

    @Test
    fun `entity without a key keeps its path while its file is missing`() {
        every { entityService.getByPath(any(), any()) } returns Result.ok(entity(path.pathString, fileKey = null))
        every { filesystemService.exists(any(), any()) } returns false
        fileOnDiskHasKey(null)

        assertRejected(service.verifyEntityIdentity(path, action))

        verify(exactly = 0) { entityService.move(any(), any(), any()) }
    }

    @Test
    fun `indexing creates an entity for an untracked file`() {
        val ownerId = UlidCreator.getUlid()
        fileOnDiskHasKey("k7")
        every { entityService.create(any(), any(), any()) } returns Result.ok(entity(path.pathString, fileKey = "k7"))

        assertSuccess(service.ensureEntityIndexed(path, ownerId, action))

        verify(exactly = 1) { entityService.create(path, ownerId, action) }
    }

    @Test
    fun `indexing does not create a second entity for an already tracked file`() {
        every { entityService.getByPath(any(), any()) } returns Result.ok(entity(path.pathString, fileKey = "k7"))
        fileOnDiskHasKey("k7")

        assertSuccess(service.ensureEntityIndexed(path, UlidCreator.getUlid(), action))

        verify(exactly = 0) { entityService.create(any(), any(), any()) }
    }
}
