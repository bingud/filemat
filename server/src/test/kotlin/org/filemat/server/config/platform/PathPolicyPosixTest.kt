package org.filemat.server.config.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Paths

/**
 * Tests the POSIX path policy. It replaced string path operations, so the results must match the old behaviour.
 */
class PathPolicyPosixTest {

    private val policy = PosixPathPolicy

    @Nested
    inner class Segments {

        @Test
        fun `splits and drops empty segments`() {
            assertEquals(listOf("a", "b"), policy.segments("/a//b/"))
            assertEquals(emptyList<String>(), policy.segments("/"))
            assertEquals(emptyList<String>(), policy.segments(""))
        }

        @Test
        fun `backslash stays part of the name`() {
            assertEquals(listOf("a\\b", "c"), policy.segments("/a\\b/c"))
        }

        @Test
        fun `join builds an absolute path`() {
            assertEquals("/a/b", policy.join(listOf("a", "b")))
            assertEquals("/", policy.join(emptyList()))
        }
    }

    @Nested
    @DisabledOnOs(OS.WINDOWS)
    inner class Parse {

        @Test
        fun `parse normalizes and makes absolute`() {
            assertEquals("/a/c", policy.toApiString(policy.parse("a/b/../c")))
            assertEquals("/a/c", policy.toApiString(policy.parse("//a/./c/")))
            assertEquals("/", policy.toApiString(policy.parse("/../..")))
        }

    }

    @Nested
    inner class IsRoot {

        // Runs on every OS: on Windows this policy parses `/` to `\`, which must still count as root
        @Test
        fun `parsed root is the root`() {
            assertTrue(policy.isRoot(policy.parse("/")))
            assertTrue(policy.isRoot(policy.parse("/a/..")))
            assertTrue(policy.isRoot(policy.parse("")))
        }

        @Test
        fun `other paths are not the root`() {
            assertFalse(policy.isRoot(policy.parse("/a")))
            assertFalse(policy.isRoot(policy.parse("/a/b")))
        }

        @Test
        fun `a relative path is not the root`() {
            assertFalse(policy.isRoot(Paths.get("")))
            assertFalse(policy.isRoot(Paths.get("a")))
        }

        @Test
        @EnabledOnOs(OS.WINDOWS)
        fun `a drive root is the root`() {
            assertTrue(policy.isRoot(Paths.get("C:\\")))
            assertFalse(policy.isRoot(Paths.get("C:\\Users")))
        }
    }

    @Nested
    inner class FileNames {

        @Test
        fun `normal names pass`() {
            assertTrue(policy.validateFileName("file.txt").isSuccessful)
            assertTrue(policy.validateFileName("CON").isSuccessful)
            assertTrue(policy.validateFileName("trailing. ").isSuccessful)
            assertTrue(policy.validateFileName("back\\slash").isSuccessful)
        }

        @Test
        fun `invalid names are rejected`() {
            assertFalse(policy.validateFileName("").isSuccessful)
            assertFalse(policy.validateFileName(".").isSuccessful)
            assertFalse(policy.validateFileName("..").isSuccessful)
            assertFalse(policy.validateFileName("a/b").isSuccessful)
            assertFalse(policy.validateFileName("a\u0000b").isSuccessful)
        }

        @Test
        fun `length is counted in UTF-8 bytes`() {
            assertTrue(policy.validateFileName("a".repeat(255)).isSuccessful)
            assertFalse(policy.validateFileName("a".repeat(256)).isSuccessful)
            // 2 bytes each in UTF-8
            assertFalse(policy.validateFileName("ä".repeat(128)).isSuccessful)
        }
    }
}
