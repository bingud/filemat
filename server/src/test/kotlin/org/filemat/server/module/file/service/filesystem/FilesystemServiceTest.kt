package org.filemat.server.module.file.service.filesystem

import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.testutil.assertNotFound
import org.filemat.server.testutil.assertSuccess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
}
