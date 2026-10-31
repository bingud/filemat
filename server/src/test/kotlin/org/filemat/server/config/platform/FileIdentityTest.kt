package org.filemat.server.config.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FileIdentityTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `a file key survives a rename and a move to another folder`() {
        val file = Files.writeString(dir.resolve("a.txt"), "a")
        val key = FileIdentity.fileKey(file)
        assertNotNull(key)

        val renamed = Files.move(file, dir.resolve("b.txt"))
        assertEquals(key, FileIdentity.fileKey(renamed))

        val moved = Files.move(renamed, Files.createDirectory(dir.resolve("sub")).resolve("b.txt"))
        assertEquals(key, FileIdentity.fileKey(moved))
    }

    @Test
    fun `a copy gets a new key`() {
        val file = Files.writeString(dir.resolve("a.txt"), "a")
        val copy = Files.copy(file, dir.resolve("copy.txt"))

        assertNotEquals(FileIdentity.fileKey(file), FileIdentity.fileKey(copy))
    }

    @Test
    fun `hard links share a key`() {
        val file = Files.writeString(dir.resolve("a.txt"), "a")
        val link = Files.createLink(dir.resolve("link.txt"), file)

        assertEquals(FileIdentity.fileKey(file), FileIdentity.fileKey(link))
    }

    @Test
    fun `folders have keys`() {
        assertNotNull(FileIdentity.fileKey(Files.createDirectory(dir.resolve("folder"))))
    }

    @Test
    fun `a missing file has no key and no support answer`() {
        assertNull(FileIdentity.fileKey(dir.resolve("missing")))
        assertNull(FileIdentity.isSupported(dir.resolve("missing")))
    }

    @Test
    fun `the temp folder is on a supported filesystem`() {
        assertEquals(true, FileIdentity.isSupported(dir))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `Windows keys hold the volume serial and the 128-bit file ID`() {
        val key = FileIdentity.fileKey(Files.writeString(dir.resolve("a.txt"), "a"))

        assertTrue(Regex("win:[0-9a-f]{16}:[0-9a-f]{32}").matches(key.orEmpty()), key)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `the drive list has no key, so it never takes the current drive's`() {
        val driveList = PathPolicy.parse("/")

        assertNull(FileIdentity.fileKey(driveList))
        assertEquals(false, FileIdentity.isSupported(driveList))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `Windows volume roots end with a backslash`() {
        val root = WindowsNative.volumeRoot(dir)

        assertNotNull(root)
        assertTrue(root.orEmpty().endsWith("\\"), root)
        assertTrue(dir.toString().startsWith(root.orEmpty(), ignoreCase = true), root)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `POSIX keys hold the device and the inode`() {
        val file = Files.writeString(dir.resolve("a.txt"), "a")
        val ino = Files.getAttribute(file, "unix:ino")
        val dev = Files.getAttribute(file, "unix:dev")

        assertEquals("posix:$dev:$ino", FileIdentity.fileKey(file))
    }

    @Test
    fun `folders shared from the host are unstable mounts`() {
        val mounts = listOf(
            "overlay / overlay rw 0 0",
            "C:\\134 /mnt/c 9p rw 0 0",
            "grpcfuse /data fuse.grpcfuse rw 0 0",
            "/dev/sdb1 /data/disk ext4 rw 0 0",
            "C:\\134My\\040Files /my\\040files fakeowner rw 0 0",
        )

        assertEquals("9p", FileIdentity.unstableMountType("/mnt/c/Users", mounts))
        assertEquals("fuse.grpcfuse", FileIdentity.unstableMountType("/data", mounts))
        assertNull(FileIdentity.unstableMountType("/data/disk/photos", mounts))
        assertNull(FileIdentity.unstableMountType("/database", mounts))
        assertNull(FileIdentity.unstableMountType("/srv", mounts))
        assertEquals("fakeowner", FileIdentity.unstableMountType("/my files/a", mounts))
    }

    @Test
    fun `a later mount on the same folder hides the earlier one`() {
        val mounts = listOf("C: /data 9p rw 0 0", "/dev/sdb1 /data ext4 rw 0 0")

        assertNull(FileIdentity.unstableMountType("/data/a", mounts))
    }
}
