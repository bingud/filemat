package org.filemat.server.common.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.filemat.server.common.model.Result
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.LockType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicInteger

class SafeWalkTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `walks nested real directories`() = runBlocking {
        val root = Files.createDirectory(tempDir.resolve("root"))
        val nested = Files.createDirectory(root.resolve("nested"))
        val file = Files.writeString(nested.resolve("file.txt"), "ok")

        val walked = root.safeWalk().toList()

        assertTrue(walked.contains(root))
        assertTrue(walked.contains(nested))
        assertTrue(walked.contains(file))
    }

    @Test
    fun `does not walk into directory symlinks`() = runBlocking {
        val allowed = Files.createDirectory(tempDir.resolve("allowed"))
        val denied = Files.createDirectory(tempDir.resolve("denied"))
        val visible = Files.writeString(allowed.resolve("visible.txt"), "ok")
        val secret = Files.writeString(denied.resolve("secret.txt"), "no")

        val link = allowed.resolve("to-denied")
        createSymbolicLinkOrSkip(link, denied)

        val walked = allowed.safeWalk().toList()

        assertTrue(walked.contains(allowed))
        assertTrue(walked.contains(visible))
        assertTrue(walked.contains(link))
        assertFalse(walked.contains(secret))
        assertFalse(walked.any { it.fileName.toString() == "secret.txt" })
    }

    @Test
    fun `measureFolderContents counts nested files and excludes the root`() {
        val root = Files.createDirectory(tempDir.resolve("root"))
        val nested = Files.createDirectory(root.resolve("nested"))
        val deeper = Files.createDirectory(nested.resolve("deeper"))
        Files.writeString(root.resolve("a.txt"), "aa")
        Files.writeString(nested.resolve("b.txt"), "bbbb")
        Files.writeString(deeper.resolve("c.txt"), "c")

        val result = measureFolder(root)

        assertTrue(result.isSuccessful)
        val size = result.value
        assertEquals(3, size.fileCount)
        assertEquals(2, size.folderCount)
        assertEquals(2 + 4 + 1, size.totalSize)
        assertEquals(0, size.failedFolderCount)
    }

    @Test
    fun `measureFolderContents treats an empty folder as success with zero totals`() {
        val root = Files.createDirectory(tempDir.resolve("empty"))

        val result = measureFolder(root)

        assertTrue(result.isSuccessful)
        val size = result.value
        assertEquals(0, size.fileCount)
        assertEquals(0, size.folderCount)
        assertEquals(0, size.totalSize)
        assertEquals(0, size.failedFolderCount)
    }

    @Test
    fun `measureFolderContents fails when the root read lock cannot be obtained`() {
        val root = Files.createDirectory(tempDir.resolve("locked"))
        Files.writeString(root.resolve("a.txt"), "aa")
        val locks = FileLockService()
        val writeLock = locks.getLock(root, LockType.WRITE)
        assertTrue(writeLock.successful)

        try {
            val result = runBlocking(Dispatchers.IO) { root.measureFolderContents(with = locks) }
            assertTrue(result.rejected)
            assertEquals("This folder is currently being modified.", result.error)
        } finally {
            writeLock.unlock()
        }
    }

    @Test
    fun `measureFolderContents fails when the root directory cannot be listed`() {
        val root = Files.createDirectory(tempDir.resolve("unreadable"))
        Files.writeString(root.resolve("a.txt"), "aa")
        val restore = makeDirectoryUnreadableOrSkip(root)

        try {
            val failed = AtomicInteger()
            val walked = runBlocking { root.safeWalk(onListFailed = { failed.incrementAndGet() }).toList() }
            assertTrue(walked.contains(root))
            assertEquals(1, failed.get())

            val result = measureFolder(root)
            assertTrue(result.hasError)
            assertEquals("Failed to read this folder.", result.error)
        } finally {
            restore()
        }
    }

    @Test
    fun `measureFolderContents counts a nested listing failure without failing the root`() {
        val root = Files.createDirectory(tempDir.resolve("root"))
        val nested = Files.createDirectory(root.resolve("nested"))
        Files.writeString(root.resolve("a.txt"), "aa")
        Files.writeString(nested.resolve("hidden.txt"), "secret")
        val restore = makeDirectoryUnreadableOrSkip(nested)

        try {
            val result = measureFolder(root)
            assertTrue(result.isSuccessful)
            val size = result.value
            assertEquals(1, size.fileCount)
            assertEquals(1, size.folderCount)
            assertEquals(2, size.totalSize)
            assertEquals(1, size.failedFolderCount)
        } finally {
            restore()
        }
    }

    @Test
    fun `measureFolderContents counts symlinks as files and excludes their targets`() {
        val root = Files.createDirectory(tempDir.resolve("root"))
        val outsideDir = Files.createDirectory(tempDir.resolve("outside-dir"))
        val outsideFile = Files.writeString(tempDir.resolve("outside-file.txt"), "TARGETDATA")
        Files.writeString(outsideDir.resolve("secret.txt"), "secret")
        Files.writeString(root.resolve("local.txt"), "ok")

        val dirLink = root.resolve("to-outside-dir")
        val fileLink = root.resolve("to-outside-file")
        createSymbolicLinkOrSkip(dirLink, outsideDir)
        createSymbolicLinkOrSkip(fileLink, outsideFile)

        val result = measureFolder(root)

        assertTrue(result.isSuccessful)
        val size = result.value
        val dirLinkSize = Files.readAttributes(dirLink, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).size()
        val fileLinkSize = Files.readAttributes(fileLink, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).size()

        assertEquals(3, size.fileCount)
        assertEquals(0, size.folderCount)
        assertEquals(2 + dirLinkSize + fileLinkSize, size.totalSize)
        assertEquals(0, size.failedFolderCount)
    }

    @Test
    fun `measureFolderContents propagates cancellation instead of returning a result`() = runBlocking {
        val root = Files.createDirectory(tempDir.resolve("cancelled"))
        Files.writeString(root.resolve("a.txt"), "aa")

        val parent = Job()
        var result: Result<FolderSize>? = null
        var thrown: Throwable? = null

        val child = launch(parent) {
            parent.cancel()
            try {
                result = root.measureFolderContents()
            } catch (e: CancellationException) {
                thrown = e
                throw e
            }
        }
        child.join()

        assertNull(result)
        assertTrue(thrown is CancellationException)
    }

    @Test
    fun `safeWalk stops emitting after the collecting coroutine is cancelled`() = runBlocking {
        val root = Files.createDirectory(tempDir.resolve("cancel-walk"))
        var current = root
        repeat(40) { i ->
            current = Files.createDirectory(current.resolve("d$i"))
            Files.writeString(current.resolve("f.txt"), "x")
        }

        val emitted = AtomicInteger()
        val job = launch {
            root.safeWalk().collect {
                emitted.incrementAndGet()
                yield()
            }
        }
        while (emitted.get() < 5) {
            yield()
        }
        job.cancelAndJoin()
        val afterCancel = emitted.get()

        assertTrue(job.isCancelled)
        assertTrue(afterCancel < 80)
        assertEquals(afterCancel, emitted.get())
    }

    private fun measureFolder(path: Path, with: FileLockService? = null) =
        runBlocking { path.measureFolderContents(with) }

    private fun createSymbolicLinkOrSkip(link: Path, target: Path) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (_: Exception) {
            assumeTrue(false, "Symbolic links are not available on this system")
        }
    }

    private fun makeDirectoryUnreadableOrSkip(directory: Path): () -> Unit {
        assumeTrue(
            directory.fileSystem.supportedFileAttributeViews().contains("posix"),
            "POSIX permissions are required"
        )
        val original = Files.getPosixFilePermissions(directory)
        Files.setPosixFilePermissions(directory, emptySet())
        val listingFailed = try {
            Files.newDirectoryStream(directory).use { it.toList() }
            false
        } catch (_: Exception) {
            true
        }
        if (!listingFailed) {
            Files.setPosixFilePermissions(directory, original)
            assumeTrue(false, "Process can still list the directory after clearing POSIX permissions")
        }
        return { Files.setPosixFilePermissions(directory, original) }
    }
}
