package org.filemat.server.common.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Path

@EnabledOnOs(OS.WINDOWS)
class PathPolicyWindowsTest {
    @Test
    fun `normalizes backslashes to slash storage strings`() {
        val path = Path.of("C:\\Users\\Alice\\Documents")

        assertEquals("C:/Users/Alice/Documents", PathPolicy.toStorageString(path))
    }

    @Test
    fun `path keys are case insensitive on Windows`() {
        assertEquals(
            PathPolicy.toPathKey("C:/Users/Alice/File.txt"),
            PathPolicy.toPathKey("c:/users/alice/file.txt")
        )
    }

    @Test
    fun `rejects Windows unsafe filenames`() {
        assertFalse(PathPolicy.validateFileName("CON").isSuccessful)
        assertFalse(PathPolicy.validateFileName("notes.txt:secret").isSuccessful)
        assertFalse(PathPolicy.validateFileName("trailing.").isSuccessful)
        assertFalse(PathPolicy.validateFileName("bad\\name.txt").isSuccessful)
        assertTrue(PathPolicy.validateFileName("normal file.txt").isSuccessful)
    }

    @Test
    fun `share child paths must stay relative and contained`() {
        val base = Path.of("C:\\shares\\public")

        assertTrue(PathPolicy.resolveContainedRelative(base, "folder\\file.txt").isSuccessful)
        assertFalse(PathPolicy.resolveContainedRelative(base, "..\\secret.txt").isSuccessful)
        assertFalse(PathPolicy.resolveContainedRelative(base, "C:\\Windows\\win.ini").isSuccessful)
        assertFalse(PathPolicy.resolveContainedRelative(base, "\\\\server\\share\\file.txt").isSuccessful)
    }

    @Test
    fun `startsWith compares paths case insensitively on Windows`() {
        assertTrue(PathPolicy.startsWith(Path.of("C:\\DATA\\Folder\\file.txt"), Path.of("c:\\data")))
    }
}
