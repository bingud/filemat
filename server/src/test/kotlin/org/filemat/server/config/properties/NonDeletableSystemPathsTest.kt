package org.filemat.server.config.properties

import org.filemat.server.config.platform.WindowsSystemFolders
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NonDeletableSystemPathsTest {

    private val folders = WindowsSystemFolders.of(
        env = mapOf(
            "SystemDrive" to "C:",
            "SystemRoot" to "C:\\Windows",
            "ProgramData" to "C:\\ProgramData",
            "ProgramFiles" to "C:\\Program Files",
            "ProgramFiles(x86)" to "C:\\Program Files (x86)",
        ),
        profiles = "C:\\Users",
    )
    private val windows = NonDeletableSystemPaths.toExactRegexList(NonDeletableSystemPaths.windowsList(folders), ignoreCase = true)

    private fun isProtected(path: String) = windows.any { it.matches(path) }

    @Test
    fun `Windows system folders are protected in any case`() {
        for (path in listOf("C:/Windows", "c:/windows", "C:/Program Files", "C:/Program Files (x86)", "C:/ProgramData", "C:/Recovery")) {
            assertTrue(isProtected(path), path)
        }
    }

    @Test
    fun `every user profile is protected, but not its contents`() {
        assertTrue(isProtected("C:/Users"))
        assertTrue(isProtected("C:/Users/bob"))
        assertTrue(isProtected("C:/Users/Public"))
        assertFalse(isProtected("C:/Users/bob/Documents"))
    }

    @Test
    fun `contents of system folders can be deleted`() {
        assertFalse(isProtected("C:/Windows/Temp/file.tmp"))
        assertFalse(isProtected("C:/Program Files/App"))
    }

    @Test
    fun `Linux folders match exactly`() {
        val posix = NonDeletableSystemPaths.toExactRegexList(listOf("/etc", "/"), ignoreCase = false)

        assertTrue(posix.any { it.matches("/etc") })
        assertTrue(posix.any { it.matches("/") })
        assertFalse(posix.any { it.matches("/etc/app.conf") })
        assertFalse(posix.any { it.matches("/ETC") })
    }
}
