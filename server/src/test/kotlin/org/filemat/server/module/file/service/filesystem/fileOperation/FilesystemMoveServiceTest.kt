package org.filemat.server.module.file.service.filesystem.fileOperation

import io.mockk.every
import io.mockk.mockk
import org.filemat.server.common.model.Result
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.file.ThumbnailService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.log.service.LogService
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FilesystemMoveServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val fileService = mockk<FileService>()
    private val entityService = mockk<EntityService>()
    private val thumbnailService = mockk<ThumbnailService>(relaxed = true)
    private val filesystemService = mockk<FilesystemService>(relaxed = true)
    private val logService = mockk<LogService>(relaxed = true)
    private val user = mockk<Principal>(relaxed = true)

    private val movable = mutableSetOf<Path>()

    private val service = FilesystemMoveService(
        logService = logService,
        fileService = fileService,
        fileLockService = FileLockService(),
        entityService = entityService,
        filesystemService = filesystemService,
        thumbnailService = thumbnailService,
    )

    @BeforeEach
    fun setup() {
        movable.clear()
        every { fileService.isAllowedToMoveFile(any(), any(), any()) } answers {
            val path = (invocation.args[1] as FilePath).path
            if (path in movable) Result.ok() else Result.reject("You do not have permission to move this file.")
        }
        every { entityService.move(any(), any(), any()) } returns Result.ok()
        every { entityService.duplicateEntity(any(), any(), any()) } returns Result.ok(mockk())
    }

    @Test
    fun `read-only folder does not create dest folder`() {
        val source = tempDir.resolve("folder")
        val destParent = tempDir.resolve("dest")
        val dest = destParent.resolve("folder")
        Files.createDirectories(source)
        Files.createDirectories(destParent)
        Files.writeString(source.resolve("file.txt"), "content")

        val result = move(source, dest)

        assertTrue(result.isNotSuccessful)
        assertFalse(Files.exists(dest))
        assertTrue(Files.exists(source.resolve("file.txt")))
    }

    @Test
    fun `empty folder with MOVE is created at dest`() {
        val source = tempDir.resolve("empty")
        val destParent = tempDir.resolve("dest")
        val dest = destParent.resolve("empty")
        Files.createDirectories(source)
        Files.createDirectories(destParent)
        movable.add(source)

        val result = move(source, dest)

        assertTrue(result.isSuccessful)
        assertTrue(Files.isDirectory(dest))
        assertFalse(Files.exists(source))
    }

    @Test
    fun `nested MOVE still creates dest path for allowed file`() {
        val source = tempDir.resolve("folder")
        val nested = source.resolve("nested")
        val destParent = tempDir.resolve("dest")
        val dest = destParent.resolve("folder")
        Files.createDirectories(nested)
        Files.createDirectories(destParent)
        Files.writeString(nested.resolve("allowed.txt"), "ok")
        Files.writeString(source.resolve("denied.txt"), "no")
        movable.add(nested.resolve("allowed.txt"))

        val result = move(source, dest)

        assertTrue(result.isNotSuccessful)
        assertTrue(Files.exists(dest.resolve("nested").resolve("allowed.txt")))
        assertFalse(Files.exists(dest.resolve("denied.txt")))
        assertTrue(Files.exists(source.resolve("denied.txt")))
        assertFalse(Files.exists(nested.resolve("allowed.txt")))
    }

    @Test
    fun `folder with MOVE but no movable children does not create dest`() {
        val source = tempDir.resolve("folder")
        val destParent = tempDir.resolve("dest")
        val dest = destParent.resolve("folder")
        Files.createDirectories(source)
        Files.createDirectories(destParent)
        Files.writeString(source.resolve("file.txt"), "content")
        movable.add(source)

        val result = move(source, dest)

        assertTrue(result.isNotSuccessful)
        assertFalse(Files.exists(dest))
        assertTrue(Files.exists(source.resolve("file.txt")))
    }

    private fun move(source: Path, dest: Path) = service.moveFile(
        source = FilePath.ofAlreadyNormalized(source),
        destination = FilePath.ofAlreadyNormalized(dest),
        user = user,
        ignorePermissions = false,
        isRename = false,
    )
}
