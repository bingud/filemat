package org.filemat.server.common

import org.filemat.server.common.util.normalizePath
import org.filemat.server.config.platform.Platform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Tests how [State] reads the folder list environment variables.
 */
class StateEnvTest {

    private val separator = Platform.listSeparator

    @Test
    fun `folder lists are split on the OS list separator and normalized`() {
        val state = State(env = mapOf("FM_NON_SENSITIVE_FOLDERS" to "/a/${separator}/b/../c"))

        assertEquals(setOf("/a".normalizePath(), "/c".normalizePath()), state.app.nonSensitiveFolders)
    }

    @Test
    fun `every folder list variable uses the same separator`() {
        val state = State(env = mapOf(
            "FM_HIDDEN_FOLDER_PATHS" to "/h1${separator}/h2",
            "FM_FORCE_DELETABLE_FOLDERS" to "/d1${separator}/d2",
        ))

        assertEquals(setOf("/h1".normalizePath(), "/h2".normalizePath()), state.app.hiddenFolders)
        assertEquals(setOf("/d1".normalizePath(), "/d2".normalizePath()), state.app.forceDeletableFolders)
    }

    @Test
    fun `blank entries are ignored`() {
        // An empty entry would otherwise become the root folder
        val state = State(env = mapOf("FM_HIDDEN_FOLDER_PATHS" to "/a${separator}${separator} "))

        assertEquals(setOf("/a".normalizePath()), state.app.hiddenFolders)
    }

    @Test
    fun `patterns with a star are kept`() {
        val state = State(env = mapOf("FM_NON_SENSITIVE_FOLDERS" to "/home/*/.ssh"))

        assertEquals(setOf("/home/*/.ssh"), state.app.nonSensitiveFolders)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `Windows patterns with a star get forward slashes`() {
        val state = State(env = mapOf("FM_NON_SENSITIVE_FOLDERS" to "C:\\Users\\*\\.ssh"))

        assertEquals(setOf("C:/Users/*/.ssh"), state.app.nonSensitiveFolders)
    }

    @Test
    fun `missing variables give empty lists`() {
        val state = State(env = emptyMap())

        assertEquals(emptySet<String>(), state.app.nonSensitiveFolders)
        assertEquals(emptySet<String>(), state.app.hiddenFolders)
    }

    @Test
    fun `an entry with a star in a whole-path list gets a warning that names it`() {
        val warnings = wildcardWarnings("FM_HIDDEN_FOLDER_PATHS", listOf("/data/*", "/plain", "/a/*/b"))

        assertEquals(2, warnings.size)
        assertTrue("FM_HIDDEN_FOLDER_PATHS" in warnings[0] && "\"/a/*/b\"" in warnings[0], warnings[0])
        assertTrue("wildcards (*) are not supported" in warnings[0], warnings[0])
        assertTrue("\"/data/*\"" in warnings[1], warnings[1])
    }

    @Test
    fun `the warnings are printed at startup for the hidden and force-deletable lists only`() {
        val printed = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(printed, true, Charsets.UTF_8))
        try {
            State(env = mapOf(
                "FM_HIDDEN_FOLDER_PATHS" to "/hidden/*",
                "FM_FORCE_DELETABLE_FOLDERS" to "/deletable/*",
                "FM_NON_SENSITIVE_FOLDERS" to "/home/*/.ssh",
            ))
        } finally {
            System.setOut(original)
        }
        val warnings = printed.toString(Charsets.UTF_8).lines().filter { it.startsWith("[!]") }

        assertEquals(2, warnings.size, warnings.toString())
        assertTrue(warnings.any { "FM_HIDDEN_FOLDER_PATHS" in it && "/hidden/*" in it }, warnings.toString())
        assertTrue(warnings.any { "FM_FORCE_DELETABLE_FOLDERS" in it && "/deletable/*" in it }, warnings.toString())
    }
}
