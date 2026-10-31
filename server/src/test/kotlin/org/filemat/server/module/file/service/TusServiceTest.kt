package org.filemat.server.module.file.service

import io.mockk.mockk
import org.filemat.server.common.State
import org.filemat.server.testutil.assertError
import org.filemat.server.testutil.assertSuccess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TusServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val service = TusService(
        filesystem = mockk(),
        fileService = mockk(),
        logService = mockk(relaxed = true),
        folderUploadService = mockk(),
        state = State(env = emptyMap()),
    )

    private fun oldFolder(): Path {
        val old = Files.createDirectory(tempDir.resolve("old"))
        Files.createDirectories(old.resolve("uploads").resolve("id"))
        Files.writeString(old.resolve("uploads").resolve("id").resolve("data"), "part")
        Files.createDirectories(old.resolve("locks"))
        Files.writeString(old.resolve("other.txt"), "stays")
        return old
    }

    @Test
    fun `uploads in progress move to the new folder`() {
        val old = oldFolder()
        val new = tempDir.resolve("new")

        assertSuccess(service.moveUploadsInProgress(old, new))

        assertEquals("part", Files.readString(new.resolve("uploads").resolve("id").resolve("data")))
        assertTrue(Files.isDirectory(new.resolve("locks")))
        assertFalse(Files.exists(old.resolve("uploads")))
        assertTrue(Files.exists(old.resolve("other.txt")))
    }

    @Test
    fun `uploads in progress stay when the new folder has its own`() {
        val old = oldFolder()
        val new = Files.createDirectories(tempDir.resolve("new").resolve("uploads"))

        assertError(service.moveUploadsInProgress(old, new.parent))

        assertTrue(Files.exists(old.resolve("uploads").resolve("id").resolve("data")))
    }

    @Test
    fun `uploads in progress stay with their locks when the new folder has only locks`() {
        val old = oldFolder()
        val new = Files.createDirectories(tempDir.resolve("new").resolve("locks")).parent

        assertError(service.moveUploadsInProgress(old, new))

        assertTrue(Files.exists(old.resolve("uploads").resolve("id").resolve("data")))
        assertTrue(Files.isDirectory(old.resolve("locks")))
        assertFalse(Files.exists(new.resolve("uploads")))
    }
}
