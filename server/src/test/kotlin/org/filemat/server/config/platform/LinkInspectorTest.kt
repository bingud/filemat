package org.filemat.server.config.platform

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.filemat.server.testutil.junctionOrSkip
import org.filemat.server.testutil.symlinkOrSkip
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.listDirectoryEntries

class LinkInspectorTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `files and folders are not links`() {
        val dir = Files.createDirectory(tempDir.resolve("dir"))
        val file = Files.writeString(tempDir.resolve("file.txt"), "x")

        assertEquals(LinkInspector.Kind.NONE, LinkInspector.kindOf(dir))
        assertEquals(LinkInspector.Kind.NONE, LinkInspector.kindOf(file))
        assertFalse(LinkInspector.isDirectoryLink(dir))
        assertTrue(LinkInspector.isRegularFile(file))
        assertFalse(LinkInspector.isRegularFile(dir))
    }

    @Test
    fun `a missing path is nothing`() {
        val missing = tempDir.resolve("missing")

        assertEquals(LinkInspector.Kind.NONE, LinkInspector.kindOf(missing))
        assertFalse(LinkInspector.isDirectoryLink(missing))
        assertFalse(LinkInspector.isRegularFile(missing))
    }

    @Test
    fun `a symlink is a link, not a file`() {
        val target = Files.writeString(tempDir.resolve("target.txt"), "x")
        val link = tempDir.resolve("link.txt")
        symlinkOrSkip(link, target)

        assertEquals(LinkInspector.Kind.LINK, LinkInspector.kindOf(link))
        assertFalse(LinkInspector.isRegularFile(link))
        // Java reports symlinks as links, so they are not "directory links"
        assertFalse(LinkInspector.isDirectoryLink(link))
    }

    @Test
    fun `a junction is a folder link`() {
        val target = Files.createDirectory(tempDir.resolve("target"))
        val junction = tempDir.resolve("junction")
        junctionOrSkip(junction, target)

        assertEquals(LinkInspector.Kind.LINK, LinkInspector.kindOf(junction))
        assertTrue(LinkInspector.isDirectoryLink(junction))
        assertFalse(LinkInspector.isRegularFile(junction))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `the reparse tag of a junction is a mount point`() {
        val target = Files.createDirectory(tempDir.resolve("target"))
        val junction = tempDir.resolve("junction")
        junctionOrSkip(junction, target)

        // IO_REPARSE_TAG_MOUNT_POINT
        assertEquals(0xA0000003.toInt(), WindowsNative.reparseTag(junction))
        assertEquals(0, WindowsNative.reparseTag(target))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a OneDrive placeholder is a file with data`() {
        val oneDrive = System.getenv("OneDrive")?.let { Path.of(it) }
        assumeTrue(oneDrive != null && Files.isDirectory(oneDrive), "No OneDrive folder")
        // Files that are only online are reparse points; downloaded files are ordinary files
        val placeholder = oneDrive!!.listDirectoryEntries().firstOrNull {
            val attributes = Files.readAttributes(it, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            !attributes.isDirectory && attributes.isOther
        }
        assumeTrue(placeholder != null, "No online-only file at the top of the OneDrive folder")

        assertEquals(LinkInspector.Kind.DATA, LinkInspector.kindOf(placeholder!!))
        assertTrue(LinkInspector.isRegularFile(placeholder))
    }

    private fun kindWithTag(tag: Int?, isDirectory: Boolean): LinkInspector.Kind {
        val attributes = mockk<BasicFileAttributes> {
            every { this@mockk.isDirectory } returns isDirectory
            every { isOther } returns true
            every { isSymbolicLink } returns false
            every { isRegularFile } returns false
        }
        mockkObject(Platform, WindowsNative)
        try {
            every { Platform.isWindows } returns true
            every { WindowsNative.reparseTag(any()) } returns tag
            return LinkInspector.kindOf(Path.of("entry"), attributes)
        } finally {
            unmockkObject(Platform, WindowsNative)
        }
    }

    @Test
    fun `name-surrogate tags are links`() {
        // IO_REPARSE_TAG_MOUNT_POINT, IO_REPARSE_TAG_SYMLINK
        assertEquals(LinkInspector.Kind.LINK, kindWithTag(0xA0000003.toInt(), isDirectory = true))
        assertEquals(LinkInspector.Kind.LINK, kindWithTag(0xA000000C.toInt(), isDirectory = false))
    }

    @Test
    fun `other tags hold file data`() {
        // IO_REPARSE_TAG_CLOUD_6 (OneDrive), IO_REPARSE_TAG_DEDUP
        assertEquals(LinkInspector.Kind.DATA, kindWithTag(0x9000601A.toInt(), isDirectory = false))
        assertEquals(LinkInspector.Kind.DATA, kindWithTag(0x9000601A.toInt(), isDirectory = true))
        assertEquals(LinkInspector.Kind.DATA, kindWithTag(0x80000013.toInt(), isDirectory = false))
    }

    @Test
    fun `an unreadable tag makes a folder a link and leaves a file alone`() {
        assertEquals(LinkInspector.Kind.LINK, kindWithTag(null, isDirectory = true))
        assertEquals(LinkInspector.Kind.NONE, kindWithTag(null, isDirectory = false))
    }

    @Test
    fun `no tag is not a reparse point`() {
        // Java also reports devices as "other"
        assertEquals(LinkInspector.Kind.NONE, kindWithTag(0, isDirectory = false))
    }
}
