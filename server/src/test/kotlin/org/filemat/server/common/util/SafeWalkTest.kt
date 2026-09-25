package org.filemat.server.common.util

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

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

        val size = root.measureFolderContents()

        assertEquals(3, size.fileCount)
        assertEquals(2, size.folderCount)
        assertEquals(2 + 4 + 1, size.totalSize)
        assertEquals(0, size.failedFolderCount)
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

        val size = root.measureFolderContents()

        val dirLinkSize = Files.readAttributes(dirLink, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).size()
        val fileLinkSize = Files.readAttributes(fileLink, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).size()

        assertEquals(3, size.fileCount)
        assertEquals(0, size.folderCount)
        assertEquals(2 + dirLinkSize + fileLinkSize, size.totalSize)
        assertEquals(0, size.failedFolderCount)
    }

    private fun createSymbolicLinkOrSkip(link: Path, target: Path) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (_: Exception) {
            assumeTrue(false, "Symbolic links are not available on this system")
        }
    }
}
