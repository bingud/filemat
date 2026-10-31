package org.filemat.server.module.file.service.filesystem.fileOperation

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import org.filemat.server.common.model.Result
import org.filemat.server.common.InternalFolder
import org.filemat.server.config.platform.Platform
import org.filemat.server.config.platform.WindowsNative
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.testutil.FileOperationMocks
import org.filemat.server.testutil.assertNotSuccessful
import org.filemat.server.testutil.assertRejected
import org.filemat.server.testutil.assertSuccess
import org.filemat.server.testutil.createCaseSensitiveFolder
import org.filemat.server.testutil.isReadOnly
import org.filemat.server.testutil.junctionOrSkip
import org.filemat.server.testutil.setReadOnly
import org.filemat.server.testutil.withFileOpen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test

class FilesystemMoveServiceTest {

    @TempDir
    lateinit var tempDir: Path

    @TempDir
    lateinit var dataFolder: Path

    private val mocks = FileOperationMocks { dataFolder }
    private val fileService = mocks.fileService
    private val entityService = mocks.entityService
    private val user = mocks.user

    private val movable = mutableSetOf<Path>()

    private val service by lazy {
        FilesystemMoveService(
            logService = mocks.logService,
            fileService = fileService,
            fileLockService = mocks.fileLockService,
            entityService = entityService,
            filesystemService = mocks.filesystemService,
            thumbnailService = mocks.thumbnailService,
            state = mocks.state,
        )
    }

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

        assertNotSuccessful(result)
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

        assertSuccess(result)
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

        assertNotSuccessful(result)
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

        assertNotSuccessful(result)
        assertFalse(Files.exists(dest))
        assertTrue(Files.exists(source.resolve("file.txt")))
    }

    @Test
    fun `junction is moved as a link without moving its target contents`() {
        val outside = tempDir.resolve("outside")
        Files.createDirectories(outside)
        val outsideFile = Files.writeString(outside.resolve("keep.txt"), "keep")
        val source = tempDir.resolve("junction")
        val dest = tempDir.resolve("moved")
        junctionOrSkip(source, outside)
        movable.add(source)

        val result = move(source, dest)

        assertSuccess(result)
        assertFalse(Files.exists(source, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.exists(dest, LinkOption.NOFOLLOW_LINKS))
        assertEquals("keep", Files.readString(outsideFile))
    }

    @Test
    fun `junction inside a moved folder is not recursed into`() {
        val outside = tempDir.resolve("outside")
        Files.createDirectories(outside)
        val outsideFile = Files.writeString(outside.resolve("keep.txt"), "keep")
        val source = tempDir.resolve("folder")
        val dest = tempDir.resolve("dest")
        Files.createDirectories(source)
        junctionOrSkip(source.resolve("junction"), outside)
        movable.add(source)
        movable.add(source.resolve("junction"))

        val result = move(source, dest)

        assertSuccess(result)
        assertFalse(Files.exists(source))
        assertEquals("keep", Files.readString(outsideFile))
    }

    private fun move(source: Path, dest: Path) = service.moveFile(
        source = FilePath.ofAlreadyNormalized(source),
        destination = FilePath.ofAlreadyNormalized(dest),
        user = user,
        ignorePermissions = false,
        isRename = false,
    )

    private fun allowRename() {
        every { fileService.isAllowedToRenameFile(any(), any(), any()) } answers {
            val path = (invocation.args[1] as FilePath).path
            if (path in movable) Result.ok() else Result.reject("You do not have permission to rename this file.")
        }
    }

    private fun names(folder: Path) = folder.listDirectoryEntries().map { it.fileName.toString() }.sorted()

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a change of case renames the file`() {
        allowRename()
        val source = Files.writeString(tempDir.resolve("photo.jpg"), "x")
        val dest = tempDir.resolve("Photo.jpg")
        movable.add(source)

        assertSuccess(move(source, dest))

        assertEquals(listOf("Photo.jpg"), names(tempDir))
        verify { entityService.move(FilePath.ofAlreadyNormalized(source), FilePath.ofAlreadyNormalized(dest), any()) }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a change of case renames a folder and keeps its contents`() {
        allowRename()
        val folder = Files.createDirectory(tempDir.resolve("folder"))
        val child = Files.writeString(folder.resolve("a.txt"), "x")
        movable.addAll(listOf(folder, child))

        assertSuccess(move(folder, tempDir.resolve("Folder")))

        assertEquals(listOf("Folder"), names(tempDir))
        assertEquals("x", Files.readString(tempDir.resolve("Folder").resolve("a.txt")))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a change of case needs rename permission on the folder contents`() {
        allowRename()
        val folder = Files.createDirectory(tempDir.resolve("folder"))
        Files.writeString(folder.resolve("a.txt"), "x")
        movable.add(folder)

        assertNotSuccessful(move(folder, tempDir.resolve("Folder")))

        assertEquals(listOf("folder"), names(tempDir))
    }

    @Test
    fun `names that differ in case are different files in a case-sensitive folder`() {
        val folder = if (Platform.isWindows) createCaseSensitiveFolder(tempDir, "cs") else Files.createDirectory(tempDir.resolve("cs"))
        val source = Files.writeString(folder.resolve("a.txt"), "a")
        Files.writeString(folder.resolve("A.txt"), "b")
        movable.add(source)

        val result = move(source, folder.resolve("A.txt"))

        assertNotSuccessful(result)
        assertEquals("A file or folder already exists at the destination.", result.error)
        assertEquals("a", Files.readString(folder.resolve("a.txt")))
        assertEquals("b", Files.readString(folder.resolve("A.txt")))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a file in use is not moved`() {
        val source = Files.writeString(tempDir.resolve("a.txt"), "x")
        val dest = Files.createDirectory(tempDir.resolve("dest")).resolve("a.txt")
        movable.add(source)

        val result = withFileOpen(source) { move(source, dest) }

        assertTrue(result.isConflict)
        assertEquals("File is in use.", result.error)
        assertTrue(Files.exists(source))
        assertFalse(Files.exists(dest))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a folder is moved except the file in use`() {
        val folder = Files.createDirectory(tempDir.resolve("folder"))
        val open = Files.writeString(folder.resolve("open.txt"), "x")
        val other = Files.writeString(folder.resolve("other.txt"), "y")
        val dest = Files.createDirectory(tempDir.resolve("dest")).resolve("folder")
        movable.addAll(listOf(folder, open, other))

        val result = withFileOpen(open) { move(folder, dest) }

        assertTrue(result.isConflict)
        assertEquals("Failed to move 1 file. Some files are in use by another program.", result.error)
        assertTrue(Files.exists(open))
        assertTrue(Files.exists(dest.resolve("other.txt")))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a read-only file is moved on the same drive and stays read-only`() {
        val source = Files.writeString(tempDir.resolve("a.txt"), "x")
        setReadOnly(source)
        val dest = Files.createDirectory(tempDir.resolve("dest")).resolve("a.txt")
        movable.add(source)

        assertSuccess(move(source, dest))

        assertFalse(Files.exists(source))
        assertTrue(isReadOnly(dest))
    }

    private fun uploadedFile(): Path {
        val uploads = Files.createDirectories(dataFolder.resolve("uploads"))
        mocks.state.app.uploadFolderPath = uploads.toString()
        val folder = Files.createDirectories(uploads.resolve("uploads").resolve("id"))
        return Files.writeString(folder.resolve("data"), "upload")
    }

    private fun moveUpload(source: Path, dest: Path) = service.moveFile(
        source = FilePath.ofAlreadyNormalized(source),
        destination = FilePath.ofAlreadyNormalized(dest),
        user = user,
        internalFolder = InternalFolder.UPLOADS,
    )

    @Test
    fun `a finished upload is moved out of the upload folder in the data folder without user checks`() {
        // The user may move nothing, and neither the data folder nor the upload folder is exposed
        val dest = tempDir.resolve("a.txt")

        assertSuccess(moveUpload(uploadedFile(), dest))

        assertEquals("upload", Files.readString(dest))
    }

    @Test
    fun `an upload is never moved into the data folder`() {
        val result = moveUpload(uploadedFile(), dataFolder.resolve("a.txt"))

        assertRejected(result)
        assertEquals("Cannot move files into Filemat data folder.", result.error)
    }

    @Test
    fun `an upload is never moved through a link into the data folder`() {
        val link = tempDir.resolve("link")
        junctionOrSkip(link, dataFolder)
        try {
            assertRejected(moveUpload(uploadedFile(), link.resolve("a.txt")))

            assertFalse(Files.exists(dataFolder.resolve("a.txt")))
        } finally {
            // Removes the link only. The temp folder cleanup can't delete a link whose target is already gone.
            Files.delete(link)
        }
    }

    @Test
    fun `an internal move only takes files from the upload folder`() {
        uploadedFile()
        val database = Files.writeString(dataFolder.resolve("filemat-server.db"), "db")
        val outside = Files.writeString(tempDir.resolve("b.txt"), "b")

        assertRejected(moveUpload(database, tempDir.resolve("db")))
        assertRejected(moveUpload(outside, tempDir.resolve("c.txt")))

        assertTrue(Files.exists(database))
        assertTrue(Files.exists(outside))
    }

    @Test
    fun `a user move of a data folder file is refused`() {
        val database = Files.writeString(dataFolder.resolve("filemat-server.db"), "db")
        movable.add(database)

        val result = move(database, tempDir.resolve("db"))

        assertRejected(result)
        assertEquals("Cannot move Filemat data folder contents.", result.error)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a case-sensitive folder stays case-sensitive, with names that differ only in case`() {
        val source = Files.createDirectory(tempDir.resolve("src"))
        val caseSensitive = createCaseSensitiveFolder(source, "cs")
        val lower = Files.writeString(caseSensitive.resolve("a.txt"), "lower")
        val upper = Files.writeString(caseSensitive.resolve("A.txt"), "upper")
        val dest = Files.createDirectory(tempDir.resolve("dest")).resolve("src")
        movable.addAll(listOf(source, caseSensitive, lower, upper))

        assertSuccess(move(source, dest))

        val movedFolder = dest.resolve("cs")
        assertEquals(true, WindowsNative.isCaseSensitiveDirectory(movedFolder))
        assertEquals(false, WindowsNative.isCaseSensitiveDirectory(dest))
        assertEquals("lower", Files.readString(movedFolder.resolve("a.txt")))
        assertEquals("upper", Files.readString(movedFolder.resolve("A.txt")))
        assertFalse(Files.exists(source))
    }
}
