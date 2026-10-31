package org.filemat.server.common.util

import java.nio.file.Files
import java.nio.file.Path
import org.filemat.server.config.platform.LinkInspector
import org.filemat.server.testutil.junctionOrSkip
import org.filemat.server.testutil.symlinkOrSkip
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * [LinkInspector.isDirectoryLink] decides whether recursive delete, move and copy may descend into a directory.
 * A wrong answer means the contents of a link target get deleted or copied instead of the link.
 */
class JunctionLikeTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `regular directory is not junction-like`() {
        val dir = Files.createDirectory(tempDir.resolve("dir"))

        assertFalse(LinkInspector.isDirectoryLink(dir))
    }

    @Test
    fun `regular file is not junction-like`() {
        val file = Files.writeString(tempDir.resolve("file.txt"), "content")

        assertFalse(LinkInspector.isDirectoryLink(file))
    }

    @Test
    fun `missing path is not junction-like`() {
        assertFalse(LinkInspector.isDirectoryLink(tempDir.resolve("missing")))
    }

    @Test
    fun `symlink to a directory is not junction-like`() {
        val target = Files.createDirectory(tempDir.resolve("target"))
        val link = tempDir.resolve("link")
        symlinkOrSkip(link, target)

        // Symlinks are handled by the symlink logic, not by the junction check
        assertFalse(LinkInspector.isDirectoryLink(link))
    }

    @Test
    fun `symlink to a file is not junction-like`() {
        val target = Files.writeString(tempDir.resolve("target.txt"), "content")
        val link = tempDir.resolve("link.txt")
        symlinkOrSkip(link, target)

        assertFalse(LinkInspector.isDirectoryLink(link))
    }

    @Test
    fun `ntfs junction is junction-like`() {
        val target = Files.createDirectory(tempDir.resolve("target"))
        val junction = tempDir.resolve("junction")
        junctionOrSkip(junction, target)

        assertTrue(LinkInspector.isDirectoryLink(junction))
    }
}
