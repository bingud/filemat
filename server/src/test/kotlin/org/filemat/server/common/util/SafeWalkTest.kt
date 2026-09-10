package org.filemat.server.common.util

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

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

    private fun createSymbolicLinkOrSkip(link: Path, target: Path) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (_: Exception) {
            assumeTrue(false, "Symbolic links are not available on this system")
        }
    }
}
