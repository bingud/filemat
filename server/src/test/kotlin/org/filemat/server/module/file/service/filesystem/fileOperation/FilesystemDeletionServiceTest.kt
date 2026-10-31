package org.filemat.server.module.file.service.filesystem.fileOperation

import io.mockk.every
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import org.filemat.server.common.InternalFolder
import org.filemat.server.common.model.Result
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.testutil.FileOperationMocks
import org.filemat.server.testutil.assertRejected
import org.filemat.server.testutil.assertSuccess
import org.filemat.server.testutil.directoryLinkOrSkip
import org.filemat.server.testutil.isReadOnly
import org.filemat.server.testutil.junctionOrSkip
import org.filemat.server.testutil.setReadOnly
import org.filemat.server.testutil.withFileOpen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

class FilesystemDeletionServiceTest {

    @TempDir
    lateinit var tempDir: Path

    @TempDir
    lateinit var dataFolder: Path

    private val mocks = FileOperationMocks { dataFolder }
    private val fileService = mocks.fileService
    private val entityService = mocks.entityService
    private val user = mocks.user
    private val service by lazy {
        FilesystemDeletionService(
            logService = mocks.logService,
            fileService = fileService,
            fileLockService = mocks.fileLockService,
            entityService = entityService,
            thumbnailService = mocks.thumbnailService,
            state = mocks.state,
        )
    }

    @BeforeEach
    fun setup() {
        every { fileService.isAllowedToDeleteFile(any(), any(), any()) } returns Result.ok()
        every { entityService.getByPath(any(), any()) } returns Result.notFound()
    }

    @Test
    fun `deletes a folder with nested files`() {
        val folder = tempDir.resolve("folder")
        Files.createDirectories(folder.resolve("nested"))
        Files.writeString(folder.resolve("nested").resolve("a.txt"), "a")
        Files.writeString(folder.resolve("b.txt"), "b")

        assertSuccess(delete(folder))

        assertFalse(Files.exists(folder))
    }

    @Test
    fun `missing path is not successful`() {
        assertTrue(delete(tempDir.resolve("missing")).isNotSuccessful)
    }

    @Test
    fun `directory link inside a folder is removed without deleting its target contents`() {
        val outside = tempDir.resolve("outside")
        Files.createDirectories(outside)
        val outsideFile = Files.writeString(outside.resolve("keep.txt"), "keep")
        val folder = tempDir.resolve("folder")
        Files.createDirectories(folder)
        Files.writeString(folder.resolve("a.txt"), "a")
        directoryLinkOrSkip(folder.resolve("link"), outside)

        assertSuccess(delete(folder))

        assertFalse(Files.exists(folder, LinkOption.NOFOLLOW_LINKS))
        assertEquals("keep", Files.readString(outsideFile))
    }

    @Test
    fun `junction inside a folder is removed without deleting its target contents`() {
        val outside = tempDir.resolve("outside")
        Files.createDirectories(outside)
        val outsideFile = Files.writeString(outside.resolve("keep.txt"), "keep")
        val folder = tempDir.resolve("folder")
        Files.createDirectories(folder)
        junctionOrSkip(folder.resolve("junction"), outside)

        assertSuccess(delete(folder))

        assertFalse(Files.exists(folder, LinkOption.NOFOLLOW_LINKS))
        assertEquals("keep", Files.readString(outsideFile))
    }

    @Test
    fun `deleting a junction directly removes only the link`() {
        val outside = tempDir.resolve("outside")
        Files.createDirectories(outside)
        val outsideFile = Files.writeString(outside.resolve("keep.txt"), "keep")
        val junction = tempDir.resolve("junction")
        junctionOrSkip(junction, outside)

        assertSuccess(delete(junction))

        assertFalse(Files.exists(junction, LinkOption.NOFOLLOW_LINKS))
        assertEquals("keep", Files.readString(outsideFile))
    }

    private fun delete(target: Path) = service.deleteFile(
        target = FilePath.ofAlreadyNormalized(target),
        user = user,
        ignorePermissions = false,
    )

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a read-only file is not deleted`() {
        val file = Files.writeString(tempDir.resolve("a.txt"), "x")
        setReadOnly(file)

        val result = delete(file)

        assertRejected(result)
        assertEquals("File is read-only.", result.error)
        assertTrue(Files.exists(file))
        assertTrue(isReadOnly(file))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a folder delete leaves read-only files and deletes the rest`() {
        val folder = Files.createDirectory(tempDir.resolve("folder"))
        val readOnly = Files.writeString(folder.resolve("keep.txt"), "x")
        Files.writeString(folder.resolve("other.txt"), "y")
        val nested = Files.createDirectories(folder.resolve("nested"))
        Files.writeString(nested.resolve("b.txt"), "z")
        setReadOnly(readOnly)

        val result = delete(folder)

        assertRejected(result)
        assertEquals("2 items could not be deleted. Read-only files were left in place.", result.error)
        assertTrue(Files.exists(readOnly))
        assertFalse(Files.exists(folder.resolve("other.txt")))
        assertFalse(Files.exists(nested))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a file in use is a conflict`() {
        val file = Files.writeString(tempDir.resolve("a.txt"), "x")

        val result = withFileOpen(file) { delete(file) }

        assertTrue(result.isConflict)
        assertEquals("File is in use.", result.error)
        assertTrue(Files.exists(file))
    }

    @Test
    fun `a path longer than 260 characters is deleted`() {
        val top = tempDir.resolve("a".repeat(50))
        var folder = top
        repeat(5) { folder = folder.resolve("b".repeat(50)) }
        Files.createDirectories(folder)
        Files.writeString(folder.resolve("file.txt"), "x")
        assertTrue(folder.toString().length > 260)

        assertSuccess(delete(top))

        assertFalse(Files.exists(top))
    }

    private fun uploadTempFolder(): Path {
        val uploads = Files.createDirectories(dataFolder.resolve("uploads"))
        mocks.state.app.uploadFolderPath = uploads.toString()
        val folder = Files.createDirectories(uploads.resolve("uploads").resolve("id"))
        Files.writeString(folder.resolve("info"), "x")
        return folder
    }

    private fun deleteUpload(target: Path) = service.deleteFile(
        target = FilePath.ofAlreadyNormalized(target),
        user = user,
        internalFolder = InternalFolder.UPLOADS,
    )

    @Test
    fun `a finished upload's temp folder is deleted from the data folder without user checks`() {
        every { fileService.isAllowedToDeleteFile(any(), any(), any()) } returns Result.reject("You do not have permission to delete this file.")
        val folder = uploadTempFolder()

        assertSuccess(deleteUpload(folder))

        assertFalse(Files.exists(folder))
    }

    @Test
    fun `an internal delete never touches the data folder itself or its other files`() {
        uploadTempFolder()
        val database = Files.writeString(dataFolder.resolve("filemat-server.db"), "db")

        assertRejected(deleteUpload(database))
        assertRejected(deleteUpload(dataFolder))

        assertTrue(Files.exists(database))
    }

    @Test
    fun `an internal delete outside the upload folder is refused`() {
        uploadTempFolder()
        val other = Files.writeString(tempDir.resolve("a.txt"), "x")

        assertRejected(deleteUpload(other))

        assertTrue(Files.exists(other))
    }

    @Test
    fun `a user delete in the data folder is refused`() {
        val database = Files.writeString(dataFolder.resolve("filemat-server.db"), "db")

        val result = delete(database)

        assertRejected(result)
        assertEquals("Cannot delete Filemat data folder.", result.error)
        assertTrue(Files.exists(database))
    }
}
