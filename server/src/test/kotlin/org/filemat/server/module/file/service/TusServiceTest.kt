package org.filemat.server.module.file.service

import io.mockk.mockk
import org.filemat.server.common.State
import org.filemat.server.config.Props
import org.filemat.server.testutil.assertError
import org.filemat.server.testutil.assertSuccess
import org.filemat.server.testutil.junctionOrSkip
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

class TusServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var dataFolder: Path
    private lateinit var state: State
    private lateinit var service: TusService

    @BeforeEach
    fun setup() {
        dataFolder = Files.createDirectory(tempDir.resolve("data")).toRealPath()
        state = State(env = emptyMap(), dataFolderPath = dataFolder)
        service = TusService(
            filesystem = mockk(),
            fileService = mockk(),
            logService = mockk(relaxed = true),
            folderUploadService = mockk(),
            state = state,
        )
    }

    private val tusName = Props.uploadSubfolderName

    /** An upload folder with an upload in progress in tus's folder, and a user's own `uploads` and `locks` folders and file */
    private fun oldFolder(): Path {
        val old = Files.createDirectory(tempDir.resolve("old"))
        Files.createDirectories(old.resolve(tusName).resolve("uploads").resolve("id"))
        Files.writeString(old.resolve(tusName).resolve("uploads").resolve("id").resolve("data"), "part")
        Files.createDirectories(old.resolve(tusName).resolve("locks"))
        addUserFiles(old)
        return old
    }

    private fun addUserFiles(folder: Path) {
        Files.createDirectories(folder.resolve("uploads").resolve("mine"))
        Files.writeString(folder.resolve("uploads").resolve("mine").resolve("a.txt"), "user's")
        Files.createDirectories(folder.resolve("locks"))
        Files.writeString(folder.resolve("locks").resolve("b.txt"), "user's")
        Files.writeString(folder.resolve("other.txt"), "stays")
    }

    private fun assertUserFilesIntact(folder: Path) {
        assertEquals("user's", Files.readString(folder.resolve("uploads").resolve("mine").resolve("a.txt")))
        assertEquals("user's", Files.readString(folder.resolve("locks").resolve("b.txt")))
        assertEquals("stays", Files.readString(folder.resolve("other.txt")))
    }

    @Test
    fun `uploads in progress move to the new folder`() {
        val old = oldFolder()
        val new = tempDir.resolve("new")

        assertSuccess(service.moveUploadsInProgress(old, new))

        assertEquals("part", Files.readString(new.resolve(tusName).resolve("uploads").resolve("id").resolve("data")))
        assertTrue(Files.isDirectory(new.resolve(tusName).resolve("locks")))
        assertFalse(Files.exists(old.resolve(tusName)))
    }

    @Test
    fun `a user's uploads and locks folders stay where they are when the upload folder changes`() {
        val old = oldFolder()
        val new = Files.createDirectory(tempDir.resolve("new"))

        assertSuccess(service.moveUploadsInProgress(old, new))

        assertUserFilesIntact(old)
        assertEquals(listOf(tusName), Files.list(new).use { list -> list.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `leftovers of the old layout stay in the old folder`() {
        // Before tus got its own folder it kept `uploads` and `locks` directly in the upload folder
        val old = Files.createDirectory(tempDir.resolve("old"))
        addUserFiles(old)
        Files.createDirectories(old.resolve("uploads").resolve("legacy"))
        val new = tempDir.resolve("new")

        assertSuccess(service.moveUploadsInProgress(old, new))

        assertUserFilesIntact(old)
        assertTrue(Files.isDirectory(old.resolve("uploads").resolve("legacy")))
        assertFalse(Files.exists(new.resolve("uploads")))
        assertFalse(Files.exists(new.resolve("locks")))
        assertFalse(Files.exists(new.resolve(tusName)))
    }

    @Test
    fun `a user's uploads and locks folders in the new folder don't stop the move and are left alone`() {
        val old = oldFolder()
        val new = Files.createDirectory(tempDir.resolve("new"))
        addUserFiles(new)

        assertSuccess(service.moveUploadsInProgress(old, new))

        assertUserFilesIntact(new)
        assertEquals("part", Files.readString(new.resolve(tusName).resolve("uploads").resolve("id").resolve("data")))
    }

    @Test
    fun `uploads in progress stay when the new folder has its own`() {
        val old = oldFolder()
        val new = Files.createDirectories(tempDir.resolve("new").resolve(tusName))

        assertError(service.moveUploadsInProgress(old, new.parent))

        assertTrue(Files.exists(old.resolve(tusName).resolve("uploads").resolve("id").resolve("data")))
        assertUserFilesIntact(old)
    }

    @Test
    fun `nothing moves when the old folder has no uploads in progress`() {
        val old = Files.createDirectory(tempDir.resolve("old"))
        addUserFiles(old)
        val new = tempDir.resolve("new")

        assertSuccess(service.moveUploadsInProgress(old, new))

        assertUserFilesIntact(old)
        assertFalse(Files.exists(new))
    }

    @Test
    fun `a link in the old folder is not moved`() {
        val old = Files.createDirectory(tempDir.resolve("old"))
        val outside = Files.createDirectory(tempDir.resolve("outside"))
        val link = old.resolve(tusName)
        junctionOrSkip(link, outside)
        try {
            assertError(service.moveUploadsInProgress(old, tempDir.resolve("new")))

            assertFalse(Files.exists(tempDir.resolve("new").resolve(tusName)))
            assertTrue(Files.exists(link, LinkOption.NOFOLLOW_LINKS))
        } finally {
            // Removes the link only. The temp folder cleanup can't delete a link whose target is already gone.
            Files.delete(link)
        }
    }

    private fun startOn(folder: Path): Boolean {
        state.app.uploadFolderPath = folder.toString()
        return service.initializeTusService()
    }

    @Test
    fun `tus gets a folder of its own inside the upload folder`() {
        val folder = tempDir.resolve("upload")

        assertTrue(startOn(folder))

        assertEquals(listOf(tusName), Files.list(folder).use { list -> list.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `tus doesn't use the user's uploads and locks folders, and its cleanup leaves their files`() {
        val folder = Files.createDirectory(tempDir.resolve("upload"))
        addUserFiles(folder)
        // Old enough for tus's lock cleanup, which deletes any entry of a `locks` folder it owns that isn't locked
        val old = FileTime.from(Instant.now().minusSeconds(3600))
        Files.setLastModifiedTime(folder.resolve("locks").resolve("b.txt"), old)
        Files.setLastModifiedTime(folder.resolve("uploads").resolve("mine"), old)

        assertTrue(startOn(folder))
        // tus makes its `uploads` and `locks` folders with the first upload, and its cleanup needs them
        Files.createDirectories(folder.resolve(tusName).resolve("uploads"))
        Files.createDirectories(folder.resolve(tusName).resolve("locks"))
        service.tusFileService.cleanup()

        assertUserFilesIntact(folder)
        assertTrue(Files.isDirectory(folder.resolve(tusName)))
        assertEquals(setOf("uploads", "locks", tusName, "other.txt"), Files.list(folder).use { list -> list.map { it.fileName.toString() }.toList().toSet() })
    }

    @Test
    fun `tus starts again on an existing folder of its own`() {
        val folder = Files.createDirectories(tempDir.resolve("upload").resolve(tusName).resolve("uploads").resolve("id"))

        assertTrue(startOn(folder.parent.parent.parent))

        assertTrue(Files.isDirectory(folder))
    }

    @Test
    fun `tus doesn't start when its folder is a file`() {
        val folder = Files.createDirectory(tempDir.resolve("upload"))
        Files.writeString(folder.resolve(tusName), "x")

        assertFalse(startOn(folder))

        assertEquals("x", Files.readString(folder.resolve(tusName)))
    }

    @Test
    fun `tus doesn't start when its folder is a link`() {
        val folder = Files.createDirectory(tempDir.resolve("upload"))
        val outside = Files.createDirectory(tempDir.resolve("outside"))
        val link = folder.resolve(tusName)
        junctionOrSkip(link, outside)
        try {
            assertFalse(startOn(folder))

            assertEquals(0, Files.list(outside).use { it.count() })
        } finally {
            Files.delete(link)
        }
    }

    @Test
    fun `tus doesn't start on the data folder`() {
        assertFalse(startOn(dataFolder))

        assertFalse(Files.exists(dataFolder.resolve(tusName)))
    }
}
