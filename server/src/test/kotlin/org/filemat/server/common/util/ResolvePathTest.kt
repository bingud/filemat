package org.filemat.server.common.util

import org.filemat.server.module.file.model.FilePath
import org.filemat.server.testutil.assertNotFound
import org.filemat.server.testutil.assertSuccess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ResolvePathTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `resolves an existing file to its real path`() {
        val file = Files.writeString(tempDir.resolve("file.txt"), "x")

        val result = resolvePath(FilePath.ofAlreadyNormalized(file), followSymlinks = false)

        assertSuccess(result)
        assertEquals(file.toRealPath(), result.value.path)
    }

    @Test
    fun `a missing file is not found unless allowed`() {
        val missing = FilePath.ofAlreadyNormalized(tempDir.resolve("missing/child.txt"))

        assertNotFound(resolvePath(missing, followSymlinks = false))
        val result = resolvePath(missing, followSymlinks = false, allowNonExistent = true)
        assertSuccess(result)
        assertEquals(tempDir.toRealPath().resolve("missing/child.txt"), result.value.path)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `the drive list resolves to itself`() {
        val result = resolvePath(FilePath.of("/"), followSymlinks = false)

        assertSuccess(result)
        assertEquals("/", result.value.pathString)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `paths without a drive are not found`() {
        // They are only valid inside a share, and would otherwise resolve against the current drive
        assertNotFound(resolvePath(FilePath.of("/Windows"), followSymlinks = true))
        assertNotFound(resolvePath(FilePath.of("/new"), followSymlinks = true, allowNonExistent = true))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a path in the wrong case resolves to the canonical case`() {
        val file = Files.writeString(tempDir.resolve("Report.txt"), "x")
        val wrongCase = FilePath.of(FilePath.ofAlreadyNormalized(file).pathString.lowercase())

        val result = resolvePath(wrongCase, followSymlinks = false)

        assertSuccess(result)
        assertEquals("Report.txt", result.value.filename)
        assertEquals(FilePath.ofAlreadyNormalized(file.toRealPath()).pathString, result.value.pathString)
    }
}
