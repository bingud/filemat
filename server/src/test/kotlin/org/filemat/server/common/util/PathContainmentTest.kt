package org.filemat.server.common.util

import java.nio.file.Path
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests the path containment helpers that decide whether a file is inside a shared or protected folder.
 * A mistake here lets a request escape its folder, so the tricky inputs (`..`, sibling prefixes) are covered.
 */
class PathContainmentTest {

    private fun p(path: String): Path = Paths.get(path)

    @Nested
    inner class IsPathInside {

        @Test
        fun `file inside the root`() {
            assertTrue(isPathInside(p("/share/a/b.txt"), p("/share")))
        }

        @Test
        fun `root itself counts as inside`() {
            assertTrue(isPathInside(p("/share"), p("/share")))
        }

        @Test
        fun `sibling with a longer name sharing the prefix is not inside`() {
            assertFalse(isPathInside(p("/share-extra/a.txt"), p("/share")))
            assertFalse(isPathInside(p("/shared"), p("/share")))
        }

        @Test
        fun `parent of the root is not inside`() {
            assertFalse(isPathInside(p("/"), p("/share")))
        }

        @Test
        fun `dot-dot segments that climb out of the root are not inside`() {
            assertFalse(isPathInside(p("/share/../etc/passwd"), p("/share")))
            assertFalse(isPathInside(p("/share/a/../../other"), p("/share")))
        }

        @Test
        fun `dot-dot segments that stay in the root are inside`() {
            assertTrue(isPathInside(p("/share/a/../b.txt"), p("/share")))
        }

        @Test
        fun `root with dot-dot segments is normalized too`() {
            assertTrue(isPathInside(p("/share/a.txt"), p("/share/sub/..")))
            assertFalse(isPathInside(p("/share/a.txt"), p("/share/sub/../other")))
        }
    }
}
