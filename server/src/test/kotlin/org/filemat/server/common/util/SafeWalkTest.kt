package org.filemat.server.common.util

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.filemat.server.common.model.Result
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.LockType
import org.filemat.server.testutil.assertError
import org.filemat.server.testutil.assertRejected
import org.filemat.server.testutil.assertSuccess
import org.filemat.server.testutil.junctionOrSkip
import org.filemat.server.testutil.symlinkOrSkip
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test

class SafeWalkTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `a link back to a folder above counts as already walked`() {
        val root = tempDir.toRealPath()
        val sub = Files.createDirectory(root.resolve("sub"))
        val loop = sub.resolve("loop")
        symlinkOrSkip(loop, root)
        val ancestors = emptySet<Path>().plusWalkIdentity(root).plusWalkIdentity(sub)

        assertTrue(loop.isAlreadyWalked(ancestors))
        assertFalse(Files.createDirectory(sub.resolve("other")).isAlreadyWalked(ancestors))
        assertFalse(root.isAlreadyWalked(emptySet()))
        // The same folder through the link is not added twice
        assertEquals(ancestors, ancestors.plusWalkIdentity(loop))
        assertEquals(2, ancestors.size)
    }

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
        symlinkOrSkip(link, denied)

        val walked = allowed.safeWalk().toList()

        assertTrue(walked.contains(allowed))
        assertTrue(walked.contains(visible))
        assertTrue(walked.contains(link))
        assertFalse(walked.contains(secret))
        assertFalse(walked.any { it.fileName.toString() == "secret.txt" })
    }

    @Test
    fun `does not walk into junctions`() = runBlocking {
        val allowed = Files.createDirectory(tempDir.resolve("allowed"))
        val denied = Files.createDirectory(tempDir.resolve("denied"))
        Files.writeString(denied.resolve("secret.txt"), "no")
        val junction = allowed.resolve("to-denied")
        junctionOrSkip(junction, denied)

        val walked = allowed.safeWalk().toList()

        assertTrue(walked.contains(junction))
        assertFalse(walked.any { it.fileName.toString() == "secret.txt" })
    }

    @Test
    fun `walks into junctions when links are followed`() = runBlocking {
        val root = Files.createDirectory(tempDir.resolve("root"))
        val target = Files.createDirectory(tempDir.resolve("target"))
        Files.writeString(target.resolve("inside.txt"), "x")
        val junction = root.resolve("junction")
        junctionOrSkip(junction, target)

        val walked = root.safeWalk(followDirectoryLinks = true).toList()

        assertTrue(walked.contains(junction.resolve("inside.txt")))
    }

    @Test
    fun `measureFolderContents counts junctions as files`() {
        val root = Files.createDirectory(tempDir.resolve("root"))
        val target = Files.createDirectory(tempDir.resolve("target"))
        Files.writeString(target.resolve("secret.txt"), "secret")
        Files.writeString(root.resolve("local.txt"), "ok")
        junctionOrSkip(root.resolve("junction"), target)

        val result = measureFolder(root)

        assertSuccess(result)
        assertEquals(2, result.value.fileCount)
        assertEquals(0, result.value.folderCount)
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

        assertSuccess(result)
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

        assertSuccess(result)
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
            assertRejected(result)
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
            assertError(result)
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
            assertSuccess(result)
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
        symlinkOrSkip(dirLink, outsideDir)
        symlinkOrSkip(fileLink, outsideFile)

        val result = measureFolder(root)

        assertSuccess(result)
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
