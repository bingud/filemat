package org.filemat.server.config.platform

import org.filemat.server.testutil.createCaseSensitiveFolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Tests the Windows path policy: `C:/` API paths, the virtual drive list at `/`, and root-relative share paths.
 */
@EnabledOnOs(OS.WINDOWS)
class PathPolicyWindowsTest {

    private val policy = WindowsPathPolicy

    @Nested
    inner class Parse {

        @Test
        fun `parses drive paths with either separator`() {
            assertEquals(Paths.get("C:\\Users\\x"), policy.parse("C:/Users/x"))
            assertEquals(Paths.get("C:\\Users\\x"), policy.parse("C:\\Users\\x"))
            assertEquals(Paths.get("C:\\Users\\x"), policy.parse("/C:/Users/x"))
        }

        @Test
        fun `upper-cases the drive letter`() {
            assertEquals("D:\\a", policy.parse("d:/a").toString())
            assertEquals("D:\\", policy.parse("d:").toString())
        }

        @Test
        fun `normalizes dot segments without leaving the drive`() {
            assertEquals(Paths.get("C:\\b"), policy.parse("C:/a/../b/./"))
            assertEquals(Paths.get("C:\\"), policy.parse("C:/.."))
        }

        @Test
        fun `the root is the virtual drive list`() {
            for (raw in listOf("/", "", "//", "\\")) {
                val path = policy.parse(raw)
                assertTrue(policy.isVirtualRoot(path), raw)
                assertEquals("/", policy.toApiString(path), raw)
            }
        }

        @Test
        fun `paths without a drive stay root-relative for shares`() {
            val path = policy.parse("/sub/file.txt")
            assertFalse(path.isAbsolute)
            assertEquals("/sub/file.txt", policy.toApiString(path))
            assertEquals("/", policy.toApiString(policy.parse("/sub/..")))
        }

        @Test
        fun `rejects drive-relative paths`() {
            assertThrows<InvalidPathException> { policy.parse("C:foo") }
        }

        @Test
        fun `rejects alternate data streams`() {
            assertThrows<InvalidPathException> { policy.parse("C:/a/file.txt:secret") }
            assertThrows<InvalidPathException> { policy.parse("C:/a/file.txt::\$DATA") }
        }

        @Test
        fun `rejects names that Win32 would change`() {
            assertThrows<InvalidPathException> { policy.parse("C:/secret.txt.") }
            assertThrows<InvalidPathException> { policy.parse("C:/folder /a") }
        }

        @Test
        fun `rejects reserved device names`() {
            for (raw in listOf("C:/CON", "C:/a/nul.txt", "C:/COM1/b", "/aux")) {
                assertThrows<InvalidPathException>(raw) { policy.parse(raw) }
            }
        }

        @Test
        fun `rejects control and invalid characters`() {
            assertThrows<InvalidPathException> { policy.parse("C:/a\u0001b") }
            assertThrows<InvalidPathException> { policy.parse("C:/a?b") }
            assertThrows<InvalidPathException> { policy.parse("C:/a|b") }
        }

        @Test
        fun `accepts names that only look reserved`() {
            assertEquals(Paths.get("C:\\CONSOLE\\COM10"), policy.parse("C:/CONSOLE/COM10"))
        }
    }

    @Nested
    inner class ToApiString {

        @Test
        fun `uses forward slashes`() {
            assertEquals("C:/Users/x", policy.toApiString(Paths.get("C:\\Users\\x")))
            assertEquals("C:/", policy.toApiString(Paths.get("C:\\")))
        }

        @Test
        fun `round-trips through parse`() {
            for (raw in listOf("C:/", "C:/a", "C:/a b/c.txt", "/", "/sub/x")) {
                assertEquals(raw, policy.toApiString(policy.parse(raw)), raw)
            }
        }
    }

    @Nested
    inner class Normalize {

        @Test
        fun `upper-cases the drive letter of native paths`() {
            assertEquals("C:\\a\\b", policy.normalize(Paths.get("c:\\a\\..\\a\\b")).toString())
            assertEquals("C:\\", policy.normalize(Paths.get("c:\\")).toString())
        }

        @Test
        fun `keeps root-relative paths root-relative`() {
            assertEquals("\\a", policy.normalize(Paths.get("\\a")).toString())
            assertEquals("\\a", policy.normalize(Paths.get("a")).toString())
        }
    }

    @Nested
    inner class Roots {

        @Test
        fun `drive roots and the drive list have no parent`() {
            assertTrue(policy.isRoot(policy.parse("/")))
            assertTrue(policy.isRoot(policy.parse("C:/")))
            assertFalse(policy.isRoot(policy.parse("C:/a")))
        }

        @Test
        fun `only the drive list is the virtual root`() {
            assertFalse(policy.isVirtualRoot(policy.parse("C:/")))
            assertFalse(policy.isVirtualRoot(policy.parse("/sub")))
        }

        @Test
        fun `the drive list contains the system drive`() {
            val systemDrive = System.getenv("SystemDrive") ?: "C:"
            val drives = policy.virtualRootEntries().map { policy.toApiString(it) }
            assertTrue(drives.contains("${systemDrive.uppercase()}/"), drives.toString())
            assertTrue(drives.all { Regex("^[A-Z]:/$").matches(it) }, drives.toString())
        }
    }

    @Nested
    inner class Segments {

        @Test
        fun `the drive is the first segment`() {
            assertEquals(listOf("C:", "Users", "x"), policy.segments("C:/Users/x"))
            assertEquals(listOf("C:"), policy.segments("C:/"))
            assertEquals(emptyList<String>(), policy.segments("/"))
        }

        @Test
        fun `join puts drive paths under the drive and others under the root`() {
            assertEquals("/", policy.join(emptyList()))
            assertEquals("C:/", policy.join(listOf("C:")))
            assertEquals("C:/a/b", policy.join(listOf("C:", "a", "b")))
            assertEquals("/sub/x", policy.join(listOf("sub", "x")))
        }
    }

    @Test
    fun `paths compare without case, like the OS`() {
        assertTrue(policy.isSamePath(Paths.get("C:\\Data"), Paths.get("c:\\data")))
    }

    @Test
    fun `paths that differ in case are different in a case-sensitive folder`(@TempDir tempDir: Path) {
        val parent = createCaseSensitiveFolder(tempDir.toRealPath(), "cs")

        assertTrue(policy.isSamePath(parent.resolve("a"), parent.resolve("a")))
        assertFalse(policy.isSamePath(parent.resolve("a"), parent.resolve("A")))
        assertFalse(policy.isSamePath(parent.resolve("a"), parent.resolve("b")))
        // The folders above it are normal ones
        assertTrue(policy.isSamePath(parent.resolve("a"), Paths.get(parent.toString().uppercase()).resolve("a")))
    }
}
