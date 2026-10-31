package org.filemat.server.config.platform

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.testutil.clearCaseSensitiveFlag
import org.filemat.server.testutil.createCaseSensitiveFolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Tests path keys (§2.3): the API path on Linux, folded per folder on Windows.
 */
class PathKeyTest {

    @Nested
    inner class KeyWithin {

        @Test
        fun `a key is within itself and its ancestors`() {
            assertTrue(PathPolicy.isKeyWithin("/a/b", "/a/b"))
            assertTrue(PathPolicy.isKeyWithin("/a/b", "/a"))
            assertTrue(PathPolicy.isKeyWithin("/a/b", "/"))
        }

        @Test
        fun `keys are compared by whole segments`() {
            assertFalse(PathPolicy.isKeyWithin("/ab", "/a"))
            assertFalse(PathPolicy.isKeyWithin("/a", "/a/b"))
        }
    }

    @Nested
    @DisabledOnOs(OS.WINDOWS)
    inner class Posix {

        @Test
        fun `the key is the path`() {
            assertEquals("/a/B/c", PathPolicy.pathKey("/a/B/c"))
            assertEquals("/", PathPolicy.pathKey("/"))
        }

        @Test
        fun `names that differ in case have different keys`() {
            assertNotEquals(FilePath.of("/a/readme").pathKey, FilePath.of("/a/README").pathKey)
        }

        @Test
        fun `every folder is case-sensitive`(@TempDir dir: Path) {
            assertTrue(CaseSensitivity.isCaseSensitive(dir))
        }
    }

    @Nested
    @EnabledOnOs(OS.WINDOWS)
    inner class Windows {

        @Test
        fun `folds names in normal folders`() {
            assertEquals("C:/FM-MISSING/READ ME.TXT", PathPolicy.pathKey("c:/fm-missing/Read Me.txt"))
            assertEquals(PathPolicy.pathKey("C:/fm-missing/a"), PathPolicy.pathKey("C:/FM-MISSING/A"))
        }

        @Test
        fun `drive roots and the drive list`() {
            assertEquals("C:/", PathPolicy.pathKey("c:/"))
            assertEquals("/", PathPolicy.pathKey("/"))
        }

        @Test
        fun `paths without a drive fold every name`() {
            assertEquals("/SUB/FILE", PathPolicy.pathKey("/sub/File"))
        }

        @Test
        fun `uses simple case mapping like NTFS`() {
            assertEquals("STRASSE", WindowsPathPolicy.foldCase("STRASSE"))
            assertEquals("STRAßE", WindowsPathPolicy.foldCase("straße"))
            assertEquals("ÄÖÜ", WindowsPathPolicy.foldCase("äöü"))
        }

        @Test
        fun `a string that does not parse is its own key`() {
            assertEquals("C:foo", PathPolicy.pathKey("C:foo"))
        }

        @Test
        fun `keeps the case of names in a case-sensitive folder`(@TempDir dir: Path) {
            val folder = createCaseSensitiveFolder(dir, "proj")
            val folderKey = PathPolicy.pathKey(folder)

            assertEquals("$folderKey/Readme", PathPolicy.pathKey(folder.resolve("Readme")))
            assertNotEquals(PathPolicy.pathKey(folder.resolve("Readme")), PathPolicy.pathKey(folder.resolve("README")))
            // The folder itself is folded, because its parent is a normal folder
            assertEquals(folderKey, PathPolicy.pathKey(dir.resolve("PROJ")))
        }

        @Test
        fun `a normal folder inside a case-sensitive folder folds again`(@TempDir dir: Path) {
            val folder = createCaseSensitiveFolder(dir, "proj")
            val inner = Files.createDirectory(folder.resolve("Inner"))
            // New folders inherit the flag, so turn it off for this one
            clearCaseSensitiveFlag(inner)

            val folderKey = PathPolicy.pathKey(folder)
            assertEquals("$folderKey/Inner/FILE.TXT", PathPolicy.pathKey(inner.resolve("file.txt")))
        }

        @Test
        fun `reads the folder flag`(@TempDir dir: Path) {
            val normal = Files.createDirectory(dir.resolve("normal"))
            val sensitive = createCaseSensitiveFolder(dir, "sensitive")

            assertEquals(false, WindowsNative.isCaseSensitiveDirectory(normal))
            assertEquals(true, WindowsNative.isCaseSensitiveDirectory(sensitive))
            assertNull(WindowsNative.isCaseSensitiveDirectory(dir.resolve("missing")))
            assertFalse(CaseSensitivity.isCaseSensitive(Paths.get(dir.resolve("missing").toString())))
        }

        @Test
        fun `a folder whose filesystem has no flag is read once, a missing folder every time`(@TempDir dir: Path) {
            val fat = Files.createDirectory(dir.resolve("fat"))
            val missing = dir.resolve("missing")

            mockkObject(WindowsNative)
            try {
                // As on FAT or exFAT
                every { WindowsNative.isCaseSensitiveDirectory(any()) } returns null
                repeat(2) {
                    assertFalse(CaseSensitivity.isCaseSensitive(fat))
                    assertFalse(CaseSensitivity.isCaseSensitive(missing))
                }

                verify(exactly = 1) { WindowsNative.isCaseSensitiveDirectory(fat) }
                verify(exactly = 2) { WindowsNative.isCaseSensitiveDirectory(missing) }
            } finally {
                unmockkObject(WindowsNative)
            }
        }
    }
}
