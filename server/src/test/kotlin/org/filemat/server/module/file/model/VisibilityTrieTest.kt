package org.filemat.server.module.file.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

class VisibilityTrieTest {

    private val trie = VisibilityTrie()

    private fun visibility(isExposed: Boolean, isExplicit: Boolean) = VisibilityTrie.Visibility(isExposed, isExplicit)

    @Nested
    inner class GetVisibility {

        @Test
        fun `nothing is exposed without rules`() {
            assertEquals(visibility(isExposed = false, isExplicit = false), trie.getVisibility("/a"))
            assertEquals(visibility(isExposed = false, isExplicit = false), trie.getVisibility("/"))
        }

        @Test
        fun `a rule applies to its own path explicitly`() {
            trie.insert("/a", isExposed = true)

            assertEquals(visibility(isExposed = true, isExplicit = true), trie.getVisibility("/a"))
        }

        @Test
        fun `children inherit the closest rule`() {
            trie.insert("/a", isExposed = true)
            trie.insert("/a/b", isExposed = false)

            assertEquals(visibility(isExposed = true, isExplicit = false), trie.getVisibility("/a/x"))
            assertEquals(visibility(isExposed = false, isExplicit = false), trie.getVisibility("/a/b/c"))
            assertEquals(visibility(isExposed = false, isExplicit = true), trie.getVisibility("/a/b"))
        }

        @Test
        fun `Windows drive paths inherit from the drive and the root`() {
            trie.insert("/", isExposed = true)
            trie.insert("D:/", isExposed = false)

            assertEquals(visibility(isExposed = true, isExplicit = false), trie.getVisibility("C:/Users/x"))
            assertEquals(visibility(isExposed = false, isExplicit = true), trie.getVisibility("D:/"))
            assertEquals(visibility(isExposed = false, isExplicit = false), trie.getVisibility("D:/data"))
        }

        @Test
        fun `a path below an existing node without a rule inherits`() {
            trie.insert("/a", isExposed = true)
            trie.insert("/a/b/c", isExposed = false)

            // `/a/b` has a node but no rule
            assertEquals(visibility(isExposed = true, isExplicit = false), trie.getVisibility("/a/b"))
        }

        @Test
        fun `a rule on the root applies to everything`() {
            trie.insert("/", isExposed = true)

            assertEquals(visibility(isExposed = true, isExplicit = true), trie.getVisibility("/"))
            assertEquals(visibility(isExposed = true, isExplicit = false), trie.getVisibility("/a/b"))
        }

        @Test
        fun `a sibling with the same prefix does not inherit`() {
            trie.insert("/a", isExposed = true)

            assertFalse(trie.getVisibility("/ab").isExposed)
        }

        @Test
        fun `extra and trailing slashes are ignored`() {
            trie.insert("/a//b/", isExposed = true)

            assertEquals(visibility(isExposed = true, isExplicit = true), trie.getVisibility("/a/b"))
            assertTrue(trie.getVisibility("a/b/c").isExposed)
        }

        @Test
        // On Windows `\` is a separator
        @DisabledOnOs(OS.WINDOWS)
        fun `a backslash is part of the name, not a separator`() {
            trie.insert("/a\\b", isExposed = true)

            assertTrue(trie.getVisibility("/a\\b").isExposed)
            assertFalse(trie.getVisibility("/a/b").isExposed)
        }
    }

    @Nested
    inner class Management {

        @Test
        fun `a later insert replaces the rule`() {
            trie.insert("/a", isExposed = true)
            trie.insert("/a", isExposed = false)

            assertFalse(trie.getVisibility("/a").isExposed)
        }

        @Test
        fun `hasExplicitRule is true only where a rule was inserted`() {
            trie.insert("/a/b", isExposed = true)

            assertTrue(trie.hasExplicitRule("/a/b"))
            assertFalse(trie.hasExplicitRule("/a"))
            assertFalse(trie.hasExplicitRule("/a/b/c"))
        }

        @Test
        fun `remove drops the rule and children inherit from above`() {
            trie.insert("/a", isExposed = true)
            trie.insert("/a/b", isExposed = false)

            trie.remove("/a/b")

            assertFalse(trie.hasExplicitRule("/a/b"))
            assertTrue(trie.getVisibility("/a/b").isExposed)
        }

        @Test
        fun `remove keeps rules below the removed path`() {
            trie.insert("/a", isExposed = true)
            trie.insert("/a/b/c", isExposed = true)

            trie.remove("/a")

            assertFalse(trie.getVisibility("/a").isExposed)
            assertTrue(trie.getVisibility("/a/b/c").isExposed)
        }

        @Test
        fun `removing a path without a rule changes nothing`() {
            trie.insert("/a/b", isExposed = true)

            trie.remove("/a")
            trie.remove("/x/y")

            assertEquals(mapOf("/a/b" to true), trie.getAllVisibilities())
        }

        @Test
        fun `getAllVisibilities lists every rule with its API path`() {
            trie.insert("/", isExposed = false)
            trie.insert("/a", isExposed = true)
            trie.insert("/a/b/c", isExposed = false)

            assertEquals(mapOf("/" to false, "/a" to true, "/a/b/c" to false), trie.getAllVisibilities())
        }

        @Test
        fun `matches by key and lists the path as entered`() {
            trie.insert("/DATA", isExposed = true, path = "/Data")

            assertTrue(trie.getVisibility("/DATA/X").isExposed)
            assertEquals(mapOf("/Data" to true), trie.getAllVisibilities())

            trie.remove("/DATA")
            assertEquals(emptyMap<String, Boolean>(), trie.getAllVisibilities())
        }
    }
}
