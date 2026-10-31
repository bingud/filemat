package org.filemat.server.module.file.service

import io.mockk.every
import io.mockk.mockk
import org.filemat.server.common.State
import org.filemat.server.config.platform.Platform
import org.filemat.server.config.platform.WindowsSystemFolders
import org.filemat.server.config.properties.SensitiveFolderPaths
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FileVisibility
import org.filemat.server.module.file.repository.FileVisibilityRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

class FileVisibilityServiceTest {

    // The system drive on Windows, `/` elsewhere
    private val root = if (Platform.isWindows) "${WindowsSystemFolders.current.systemDrive}/" else "/"
    // A file on the sensitive list of the OS the tests run on
    private val sensitiveFolder = if (Platform.isWindows) "${WindowsSystemFolders.current.systemRoot}/System32/config" else "/etc/shadow"

    private fun serviceWith(env: Map<String, String> = emptyMap()): FileVisibilityService {
        val state = State(env = env)
        val repository = mockk<FileVisibilityRepository>()
        every { repository.getAll() } returns listOf(FileVisibility(path = root, isExposed = true, createdDate = 0))
        return FileVisibilityService(repository, mockk(relaxed = true), state, SensitiveFolderPaths(state)).also { it.initialize() }
    }

    @Test
    fun `sensitive folders are blocked inside an exposed folder`() {
        val service = serviceWith()

        assertEquals("This file is blocked because it is marked as sensitive.", service.isPathAllowed(FilePath.of("$sensitiveFolder/file")))
        assertNull(service.isPathAllowed(FilePath.of("${root}some-folder")))
    }

    @Test
    fun `a folder removed from the sensitive list is allowed`() {
        val entry = if (Platform.isWindows) sensitiveFolder.lowercase() else sensitiveFolder
        val service = serviceWith(mapOf("FM_NON_SENSITIVE_FOLDERS" to entry))

        assertNull(service.isPathAllowed(FilePath.of("$sensitiveFolder/file")))
    }

    @Test
    fun `turning the sensitive list off allows sensitive folders`() {
        val service = serviceWith(mapOf("FM_HIDE_SENSITIVE_FOLDERS" to "false"))

        assertNull(service.isPathAllowed(FilePath.of("$sensitiveFolder/file")))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `system files at a drive root stay hidden when the sensitive list is off`() {
        val service = serviceWith(mapOf("FM_HIDE_SENSITIVE_FOLDERS" to "false"))

        for (name in listOf("\$Recycle.Bin/S-1-5-21", "System Volume Information", "pagefile.sys", "hiberfil.sys", "swapfile.sys")) {
            assertEquals("This file is blocked.", service.isPathAllowed(FilePath.of("$root$name")), name)
        }
        // Only at the root
        assertNull(service.isPathAllowed(FilePath.of("${root}Backups/pagefile.sys")))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `system files are hidden on a drive that was plugged in after the start`() {
        val service = serviceWith(mapOf("FM_HIDE_SENSITIVE_FOLDERS" to "false"))

        // Whether or not Q: exists, the check is by name
        assertEquals("This file is blocked.", service.isPathAllowed(FilePath.of("Q:/\$Recycle.Bin/S-1-5-21")))
        assertEquals("This file is blocked.", service.isPathAllowed(FilePath.of("Q:/system volume information")))
    }
}
