package org.filemat.server.module.file.service.filesystem

import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import org.filemat.server.config.platform.PathPolicy
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FileType
import org.filemat.server.testutil.assertNotFound
import org.filemat.server.testutil.assertRejected
import org.filemat.server.testutil.assertSuccess
import org.filemat.server.testutil.assumeWindowsFeature
import org.filemat.server.testutil.isHidden
import org.filemat.server.testutil.junctionOrSkip
import org.filemat.server.testutil.setReadOnly
import org.filemat.server.testutil.symlinkOrSkip
import org.filemat.server.testutil.withFileOpen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test

class FilesystemServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val service = FilesystemService(
        filesystemDeletionService = mockk(),
        filesystemMoveService = mockk(),
        filesystemCopyService = mockk(),
    )

    @Test
    fun `atomic replace swaps file contents and removes source`() {
        val source = tempDir.resolve("source.tmp")
        val destination = tempDir.resolve("destination.txt")
        Files.writeString(source, "new")
        Files.writeString(destination, "old")

        val result = service.replaceFileContentsAtomically(
            source = FilePath.ofAlreadyNormalized(source),
            destination = FilePath.ofAlreadyNormalized(destination),
        )

        assertSuccess(result)
        assertEquals("new", Files.readString(destination))
        assertTrue(Files.notExists(source))
    }

    @Test
    fun `missing replacement source leaves destination intact`() {
        val source = tempDir.resolve("missing.tmp")
        val destination = tempDir.resolve("destination.txt")
        Files.writeString(destination, "old")

        val result = service.replaceFileContentsAtomically(
            source = FilePath.ofAlreadyNormalized(source),
            destination = FilePath.ofAlreadyNormalized(destination),
        )

        assertNotFound(result)
        assertEquals("old", Files.readString(destination))
    }

    @Test
    fun `getMetadata reads a file with basic attributes`() {
        val file = tempDir.resolve("file.txt")
        Files.writeString(file, "12345")

        val meta = service.getMetadata(FilePath.ofAlreadyNormalized(file))

        assertNotNull(meta)
        assertEquals(FileType.FILE, meta!!.fileType)
        assertEquals(5L, meta.size)
        assertEquals(PathPolicy.toApiString(file), meta.path)
        assertEquals(Files.getLastModifiedTime(file).toMillis(), meta.modifiedDate)
        assertTrue(meta.isWritable)
    }

    @Test
    fun `getMetadata reads a folder`() {
        val folder = Files.createDirectory(tempDir.resolve("folder"))

        val meta = service.getMetadata(FilePath.ofAlreadyNormalized(folder))

        assertEquals(FileType.FOLDER, meta?.fileType)
        assertEquals(true, meta?.isExecutable)
    }

    @Test
    fun `getMetadata of a missing path is null`() {
        assertNull(service.getMetadata(FilePath.ofAlreadyNormalized(tempDir.resolve("missing"))))
    }

    @Test
    fun `getMetadata reports links by their target type`() {
        val folder = Files.createDirectory(tempDir.resolve("target-folder"))
        val file = Files.writeString(tempDir.resolve("target.txt"), "x")
        val folderLink = tempDir.resolve("folder-link")
        val fileLink = tempDir.resolve("file-link")
        symlinkOrSkip(folderLink, folder)
        symlinkOrSkip(fileLink, file)

        assertEquals(FileType.FOLDER_LINK, service.getMetadata(FilePath.ofAlreadyNormalized(folderLink))?.fileType)
        assertEquals(FileType.FILE_LINK, service.getMetadata(FilePath.ofAlreadyNormalized(fileLink))?.fileType)
    }

    @Test
    fun `getMetadata reports junctions as folder links`() {
        val folder = Files.createDirectory(tempDir.resolve("target-folder"))
        val junction = tempDir.resolve("junction")
        junctionOrSkip(junction, folder)

        assertEquals(FileType.FOLDER_LINK, service.getMetadata(FilePath.ofAlreadyNormalized(junction))?.fileType)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `getMetadata of the drive list is a read-only folder`() {
        val meta = service.getMetadata(FilePath.of("/"))

        assertEquals("/", meta?.path)
        assertEquals(FileType.FOLDER, meta?.fileType)
        assertEquals(false, meta?.isWritable)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `nothing is created in the drive list`() {
        // Java would resolve these to the current drive
        assertRejected(service.createFolder(FilePath.of("/new-folder")))
        assertRejected(service.createBlankFile(FilePath.of("/new-file.txt")))
    }

    private fun replace(source: Path, destination: Path) = service.replaceFileContentsAtomically(
        source = FilePath.ofAlreadyNormalized(source),
        destination = FilePath.ofAlreadyNormalized(destination),
    )

    private fun names() = tempDir.listDirectoryEntries().map { it.fileName.toString() }.sorted()

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `atomic replace refuses a read-only destination`() {
        val source = Files.writeString(tempDir.resolve("source.tmp"), "new")
        val destination = Files.writeString(tempDir.resolve("destination.txt"), "old")
        setReadOnly(destination)

        val result = replace(source, destination)

        assertRejected(result)
        assertEquals("File is read-only.", result.error)
        assertEquals("old", Files.readString(destination))
        assertEquals(listOf("destination.txt", "source.tmp"), names())
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `atomic replace of a file in use is a conflict and leaves no temp file`() {
        val source = Files.writeString(tempDir.resolve("source.tmp"), "new")
        val destination = Files.writeString(tempDir.resolve("destination.txt"), "old")

        val result = withFileOpen(destination) { replace(source, destination) }

        assertTrue(result.isConflict, "Got $result")
        assertEquals("File is in use.", result.error)
        assertEquals("old", Files.readString(destination))
        assertEquals(listOf("destination.txt", "source.tmp"), names())
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `atomic replace without the right to create files in the folder is access denied, not in use`() {
        val folder = Files.createDirectory(tempDir.resolve("locked"))
        val source = Files.writeString(tempDir.resolve("source.tmp"), "new")
        val destination = Files.writeString(folder.resolve("destination.txt"), "old")
        val user = System.getProperty("user.name")
        // WD on a folder is "add file". The file itself stays writable.
        val denied = runCatching { ProcessBuilder("icacls", folder.toString(), "/deny", "$user:(WD)").redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor() == 0 }.getOrDefault(false)
        assumeWindowsFeature(denied, "icacls could not deny creating files in $folder")
        try {
            assertTrue(Files.isWritable(destination))

            val result = replace(source, destination)

            assertRejected(result)
            assertFalse(result.isConflict, "Got $result")
            assertEquals("Missing permission to replace file.", result.error)
            assertEquals("old", Files.readString(destination))
        } finally {
            ProcessBuilder("icacls", folder.toString(), "/remove:d", user).redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor()
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `atomic replace does not hide the destination`() {
        val source = Files.writeString(tempDir.resolve("source.tmp"), "new")
        val destination = Files.writeString(tempDir.resolve("destination.txt"), "old")

        assertSuccess(replace(source, destination))

        assertEquals("new", Files.readString(destination))
        assertFalse(isHidden(destination))
    }
}
