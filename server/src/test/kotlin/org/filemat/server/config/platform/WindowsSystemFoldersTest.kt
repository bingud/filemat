package org.filemat.server.config.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.Path

class WindowsSystemFoldersTest {

    @Test
    fun `reads folders from the environment with forward slashes`() {
        val folders = WindowsSystemFolders.of(
            env = mapOf(
                "SystemDrive" to "D:",
                "SystemRoot" to "D:\\Windows\\",
                "ProgramData" to "D:\\ProgramData",
                "ProgramFiles" to "D:\\Program Files",
                "ProgramFiles(x86)" to "D:\\Program Files (x86)",
                "ProgramW6432" to "D:\\Program Files",
            ),
            profiles = "D:\\Users",
        )

        assertEquals("D:/Windows", folders.systemRoot)
        assertEquals("D:/ProgramData", folders.programData)
        assertEquals(listOf("D:/Program Files", "D:/Program Files (x86)"), folders.programFiles)
        assertEquals("D:/Users", folders.profiles)
    }

    @Test
    fun `missing variables fall back to the system drive`() {
        val folders = WindowsSystemFolders.of(env = mapOf("SystemDrive" to "E:"), profiles = null)

        assertEquals("E:/Windows", folders.systemRoot)
        assertEquals("E:/ProgramData", folders.programData)
        assertEquals("E:/Users", folders.profiles)
        assertEquals(listOf("E:/Program Files", "E:/Program Files (x86)"), folders.programFiles)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `the folders of this computer exist`() {
        val folders = WindowsSystemFolders.current

        assertTrue(Files.isDirectory(Path.of(folders.systemRoot)), folders.systemRoot)
        assertTrue(Files.isDirectory(Path.of(folders.profiles)), folders.profiles)
        assertTrue(Files.isDirectory(Path.of(folders.programData)), folders.programData)
    }
}
