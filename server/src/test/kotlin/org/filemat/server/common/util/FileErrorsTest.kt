package org.filemat.server.common.util

import org.filemat.server.testutil.setReadOnly
import org.filemat.server.testutil.withFileOpen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

class FileErrorsTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `other errors are not files in use`() {
        assertFalse(FileErrors.isInUse(AccessDeniedException("a")))
        assertFalse(FileErrors.isInUse(NoSuchFileException("a")))
        assertFalse(FileErrors.isInUse(IllegalStateException("a")))
        assertFalse(FileErrors.isInUse(FileSystemException("a", null, "Some other reason")))
    }

    @Test
    fun `other errors are not retried`() {
        var calls = 0
        assertThrows<NoSuchFileException> {
            FileErrors.retryWhenInUse {
                calls++
                throw NoSuchFileException("a")
            }
        }
        assertEquals(1, calls)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a file another program has open is in use`() {
        val file = Files.writeString(tempDir.resolve("a.txt"), "x")

        val error = withFileOpen(file) { runCatching { Files.delete(file) }.exceptionOrNull() }

        assertTrue(error != null && FileErrors.isInUse(error), "Got $error")
        assertEquals(FileFailure.IN_USE, error?.let { FileErrors.failureOf(it, file) })
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a delete is retried until the other program closes the file`() {
        val file = Files.writeString(tempDir.resolve("a.txt"), "x")
        val stream = FileInputStream(file.toFile())
        var calls = 0

        FileErrors.retryWhenInUse {
            calls++
            // The other program closes the file after the first try failed, not after some time
            if (calls == 2) stream.close()
            Files.delete(file)
        }

        assertEquals(2, calls)
        assertFalse(Files.exists(file))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `retries give up after three tries`() {
        val file = Files.writeString(tempDir.resolve("a.txt"), "x")
        var calls = 0

        withFileOpen(file) {
            assertThrows<FileSystemException> {
                FileErrors.retryWhenInUse {
                    calls++
                    Files.delete(file)
                }
            }
        }

        assertEquals(4, calls)
        assertTrue(Files.exists(file))
    }

    @Test
    fun `retries of one operation stop at its budget`() {
        val callsPerFile = IntArray(10)

        val startedAt = System.nanoTime()
        FileErrors.withRetryBudget(totalMs = 250) {
            for (file in callsPerFile.indices) {
                assertThrows<IOException> {
                    FileErrors.retryWhenInUse({ true }) {
                        callsPerFile[file]++
                        throw IOException("in use")
                    }
                }
            }
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        // The first file takes the whole budget (two waits), every later file fails at once
        assertEquals(3, callsPerFile[0])
        assertEquals(List(9) { 1 }, callsPerFile.drop(1))
        assertTrue(elapsedMs < 1_000, "Took $elapsedMs ms")
    }

    @Test
    fun `retries are not capped outside a budget and the budget ends with the operation`() {
        FileErrors.withRetryBudget(totalMs = 0) {
            var calls = 0
            assertThrows<IOException> { FileErrors.retryWhenInUse({ true }) { calls++; throw IOException("in use") } }
            assertEquals(1, calls)
        }

        var calls = 0
        assertThrows<IOException> { FileErrors.retryWhenInUse({ true }) { calls++; throw IOException("in use") } }
        assertEquals(4, calls)
    }

    @Test
    fun `a nested budget keeps the outer one`() {
        var calls = 0
        FileErrors.withRetryBudget(totalMs = 0) {
            FileErrors.withRetryBudget(totalMs = 10_000) {
                assertThrows<IOException> { FileErrors.retryWhenInUse({ true }) { calls++; throw IOException("in use") } }
            }
        }
        assertEquals(1, calls)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a read-only file fails to delete as read-only`() {
        val file = Files.writeString(tempDir.resolve("a.txt"), "x")
        setReadOnly(file)

        val error = runCatching { Files.delete(file) }.exceptionOrNull()

        assertTrue(error is AccessDeniedException, "Got $error")
        assertEquals(FileFailure.READ_ONLY, error?.let { FileErrors.failureOf(it, file) })
        assertTrue(Files.exists(file))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `folders are never read-only`() {
        val folder = Files.createDirectory(tempDir.resolve("folder"))
        setReadOnly(folder)

        assertFalse(FileErrors.isReadOnlyFile(folder))
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `nothing is read-only on Linux`() {
        val file = Files.writeString(tempDir.resolve("a.txt"), "x")

        assertFalse(FileErrors.isReadOnlyFile(file))
        assertFalse(FileErrors.isReadOnly(AccessDeniedException(file.toString()), file))
    }

    @Test
    fun `a single file reports only its reason`() {
        val inUse = FileFailures().apply { add(FileFailure.IN_USE) }.toResult<Unit>("1 items could not be deleted.", isSingleFile = true)
        assertTrue(inUse.isConflict)
        assertEquals("File is in use.", inUse.error)

        val readOnly = FileFailures().apply { add(FileFailure.READ_ONLY) }.toResult<Unit>("1 items could not be deleted.", isSingleFile = true)
        assertTrue(readOnly.rejected)
        assertFalse(readOnly.isConflict)
        assertEquals("File is read-only.", readOnly.error)
    }

    @Test
    fun `a folder reports how many items failed and why`() {
        val failures = FileFailures().apply {
            add(FileFailure.READ_ONLY)
            add(FileFailure.IN_USE)
            add(FileFailure.READ_ONLY)
        }

        val result = failures.toResult<Unit>("3 items could not be deleted.")

        assertTrue(result.isConflict)
        assertEquals("3 items could not be deleted. Read-only files were left in place. Some files are in use by another program.", result.error)
    }

    @Test
    fun `failures without a known reason are errors as before`() {
        val result = FileFailures().apply { add(null) }.toResult<Unit>("2 items could not be deleted.", isSingleFile = true)

        assertTrue(result.hasError)
        assertEquals("2 items could not be deleted.", result.error)
    }
}
