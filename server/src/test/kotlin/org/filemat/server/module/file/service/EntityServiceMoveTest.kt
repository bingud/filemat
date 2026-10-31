package org.filemat.server.module.file.service

import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.config.platform.PathPolicy
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FilesystemEntity
import org.filemat.server.module.file.repository.EntityRepository
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.permission.service.EntityPermissionService
import org.filemat.server.module.user.model.UserAction
import org.filemat.server.testutil.assertError
import org.filemat.server.testutil.assertSuccess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import java.nio.file.Path

/**
 * Tests how [EntityService.move] re-paths an entity and its children.
 */
class EntityServiceMoveTest {

    /** Runs the callback and its synchronizations without a database */
    private class NoOpTransactionManager : AbstractPlatformTransactionManager() {
        override fun doGetTransaction(): Any = Any()
        override fun doBegin(transaction: Any, definition: TransactionDefinition) {}
        override fun doCommit(status: DefaultTransactionStatus) {}
        override fun doRollback(status: DefaultTransactionStatus) {}
    }

    private val entityRepository = mockk<EntityRepository>()
    private val entityPermissionService = mockk<EntityPermissionService>(relaxed = true)
    private val filesystemService = mockk<FilesystemService>()

    private val service = EntityService(
        entityRepository = entityRepository,
        logService = mockk(relaxed = true),
        entityPermissionService = entityPermissionService,
        filesystemService = filesystemService,
        platformTransactionManager = NoOpTransactionManager(),
        fileShareService = mockk(),
        fileSecurityService = mockk(),
    )

    private val updatedPaths = mutableMapOf<String, String?>()
    private val updatedKeys = mutableMapOf<String, Pair<String?, Boolean>>()

    @TempDir
    lateinit var dir: Path

    init {
        every { entityRepository.updatePath(any(), any(), any()) } answers {
            val id = firstArg<Ulid>()
            updatedPaths[id.toString()] = secondArg()
            1
        }
        every { entityRepository.updateFileKey(any(), any(), any()) } answers {
            updatedKeys[firstArg<Ulid>().toString()] = secondArg<String?>() to thirdArg<Boolean>()
            1
        }
        every { entityRepository.getByFileKey(any()) } returns null
        // The moved files can't be read, so the stored keys stay
        every { filesystemService.isSupportedFilesystem(any()) } returns null
    }

    private fun entity(path: String, fileKey: String? = null) = FilesystemEntity(
        entityId = UlidCreator.getUlid(),
        path = path,
        fileKey = fileKey,
        isFilesystemSupported = true,
        ownerId = null,
        pathKey = PathPolicy.pathKey(path),
    )

    /** A folder and its file moved to [dir]/to, where the file reads as [newKey] */
    private fun moveFolderWithFile(fileKey: String?, newKey: String): Pair<FilesystemEntity, FilesystemEntity> {
        val from = PathPolicy.toApiString(dir.resolve("from"))
        val folder = entity(from, fileKey = "folder")
        val file = entity("$from/f.txt", fileKey = fileKey)
        givenEntities(from, folder, file)
        every { filesystemService.isSupportedFilesystem(any()) } returns true
        every { filesystemService.getFileKey(any()) } returns "folder"
        every { filesystemService.getFileKey(dir.resolve("to").resolve("f.txt")) } returns newKey

        assertSuccess(service.move(FilePath.of(from), FilePath.of(PathPolicy.toApiString(dir.resolve("to"))), UserAction.NONE))
        return folder to file
    }

    private fun givenEntities(prefix: String, vararg entities: FilesystemEntity) {
        every { entityRepository.getAllByPathPrefix(PathPolicy.pathKey(prefix)) } returns entities.toList()
    }

    @Test
    fun `moves a folder and re-paths its children`() {
        val folder = entity("/a/b")
        val child = entity("/a/b/c")
        val grandchild = entity("/a/b/c/d.txt")
        givenEntities("/a/b", grandchild, folder, child)

        val result = service.move(FilePath.of("/a/b"), FilePath.of("/x/y"), UserAction.NONE)

        assertSuccess(result)
        assertEquals("/x/y", updatedPaths[folder.entityId.toString()])
        assertEquals("/x/y/c", updatedPaths[child.entityId.toString()])
        assertEquals("/x/y/c/d.txt", updatedPaths[grandchild.entityId.toString()])
    }

    @Test
    fun `moving into the root gives single-slash paths`() {
        val folder = entity("/a/b")
        val child = entity("/a/b/c")
        givenEntities("/a/b", folder, child)

        assertSuccess(service.move(FilePath.of("/a/b"), FilePath.of("/b"), UserAction.NONE))

        assertEquals("/b", updatedPaths[folder.entityId.toString()])
        assertEquals("/b/c", updatedPaths[child.entityId.toString()])
    }

    @Test
    fun `after commit the cache and permission tree use the new paths`() {
        val folder = entity("/a")
        val child = entity("/a/c")
        givenEntities("/a", folder, child)

        assertSuccess(service.move(FilePath.of("/a"), FilePath.of("/z"), UserAction.NONE))

        assertEquals(child.entityId, service.map_getByPath(PathPolicy.pathKey("/z/c"))?.entityId)
        assertNull(service.map_getByPath(PathPolicy.pathKey("/a/c")))
        verify { entityPermissionService.memory_movePath(PathPolicy.pathKey("/a"), PathPolicy.pathKey("/z"), folder.entityId) }
        verify { entityPermissionService.memory_movePath(PathPolicy.pathKey("/a/c"), PathPolicy.pathKey("/z/c"), child.entityId) }
    }

    @Test
    fun `a null target unlinks the paths`() {
        val folder = entity("/a")
        val child = entity("/a/c")
        givenEntities("/a", folder, child)

        assertSuccess(service.move(FilePath.of("/a"), null, UserAction.NONE))

        assertEquals(mapOf(folder.entityId.toString() to null, child.entityId.toString() to null), updatedPaths)
        verify { entityPermissionService.memory_removeEntity(PathPolicy.pathKey("/a/c"), child.entityId) }
    }

    @Test
    fun `a rename that only changes case is not a move into itself`() {
        val folder = entity("/a/b")
        val child = entity("/a/b/c")
        givenEntities("/a/b", folder, child)

        assertSuccess(service.move(FilePath.of("/a/b"), FilePath.of("/a/B"), UserAction.NONE))

        assertEquals("/a/B", updatedPaths[folder.entityId.toString()])
        assertEquals("/a/B/c", updatedPaths[child.entityId.toString()])
        assertEquals(child.entityId, service.map_getByPath(PathPolicy.pathKey("/a/B/c"))?.entityId)
    }

    @Test
    fun `a file that got a new key on another volume is re-keyed`() {
        val (folder, file) = moveFolderWithFile(fileKey = "old", newKey = "new")

        assertEquals("new" to true, updatedKeys[file.entityId.toString()])
        assertNull(updatedKeys[folder.entityId.toString()])
        assertEquals("new", service.map_getByPath(PathPolicy.pathKey(dir.resolve("to").resolve("f.txt")))?.fileKey)
    }

    @Test
    fun `a moved hard link whose key belongs to another entity follows its path only`() {
        every { entityRepository.getByFileKey("taken") } returns entity("/elsewhere", fileKey = "taken")

        val (_, file) = moveFolderWithFile(fileKey = null, newKey = "taken")

        assertEquals(null to false, updatedKeys[file.entityId.toString()])
    }

    @Test
    fun `moving a folder into itself fails`() {
        assertError(service.move(FilePath.of("/a"), FilePath.of("/a/b"), UserAction.NONE))
        assertError(service.move(FilePath.of("/a"), FilePath.of("/a"), UserAction.NONE))
        verify(exactly = 0) { entityRepository.getAllByPathPrefix(any()) }
    }

    @Test
    fun `a sibling with the same prefix is not inside the folder`() {
        val sibling = entity("/ab")
        givenEntities("/ab", sibling)

        assertSuccess(service.move(FilePath.of("/ab"), FilePath.of("/a/b2"), UserAction.NONE))

        assertEquals("/a/b2", updatedPaths[sibling.entityId.toString()])
    }
}
