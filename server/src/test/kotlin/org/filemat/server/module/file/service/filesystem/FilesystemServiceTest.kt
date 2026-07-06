package org.filemat.server.module.file.service.filesystem

import io.mockk.mockk
import org.filemat.server.module.file.model.FilePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class FilesystemServiceTest {
    private val service = FilesystemService(
        filesystemDeletionService = mockk(),
        filesystemMoveService = mockk(),
        filesystemCopyService = mockk(),
    )

    @Test
    fun `atomic replace swaps file contents and removes source`() {
        val directory = Files.createTempDirectory("filemat-replace-test")
        val source = directory.resolve("source.tmp")
        val destination = directory.resolve("destination.txt")
        Files.writeString(source, "new")
        Files.writeString(destination, "old")

        val result = service.replaceFileContentsAtomically(
            source = FilePath.ofAlreadyNormalized(source),
            destination = FilePath.ofAlreadyNormalized(destination),
        )

        assertTrue(result.isSuccessful)
        assertEquals("new", Files.readString(destination))
        assertTrue(Files.notExists(source))
    }

    @Test
    fun `missing replacement source leaves destination intact`() {
        val directory = Files.createTempDirectory("filemat-replace-missing-test")
        val source = directory.resolve("missing.tmp")
        val destination = directory.resolve("destination.txt")
        Files.writeString(destination, "old")

        val result = service.replaceFileContentsAtomically(
            source = FilePath.ofAlreadyNormalized(source),
            destination = FilePath.ofAlreadyNormalized(destination),
        )

        assertTrue(result.notFound)
        assertEquals("old", Files.readString(destination))
    }
}
