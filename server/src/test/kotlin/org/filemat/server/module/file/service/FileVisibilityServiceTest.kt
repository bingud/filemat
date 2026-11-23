package org.filemat.server.module.file.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.filemat.server.common.State
import org.filemat.server.config.platform.Platform
import org.filemat.server.config.platform.WindowsNative
import org.filemat.server.config.platform.WindowsSystemFolders
import org.filemat.server.config.properties.SensitiveFolderPaths
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FileVisibility
import org.filemat.server.module.file.repository.FileVisibilityRepository
import org.filemat.server.module.user.model.UserAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.filemat.server.testutil.junctionOrSkip
import org.filemat.server.testutil.symlinkOrSkip
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.file.Files
import java.nio.file.Path

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

    @Test
    fun `hidden folders are hidden for folders exposed during setup, before any restart`() {
        val hidden = "${root}srv/private"
        // Not set up yet: Initialization only loads the hidden list
        val state = State(env = mapOf("FM_HIDDEN_FOLDER_PATHS" to hidden))
        val repository = mockk<FileVisibilityRepository>(relaxed = true)
        val service = FileVisibilityService(repository, mockk(relaxed = true), state, SensitiveFolderPaths(state))
        service.loadHiddenFiles()

        // What setup does
        service.insertPath(FileVisibility(path = root, isExposed = true, createdDate = 0), UserAction.APP_SETUP)

        assertEquals("This file is blocked.", service.isPathAllowed(FilePath.of("$hidden/file")))
        assertNull(service.isPathAllowed(FilePath.of("${root}srv/public")))
    }

    private fun emptyService(): FileVisibilityService {
        val state = State(env = emptyMap())
        return FileVisibilityService(mockk(relaxed = true), mockk(relaxed = true), state, SensitiveFolderPaths(state))
    }

    private fun expose(service: FileVisibilityService) =
        service.insertPath(FileVisibility(path = "${root}srv", isExposed = true, createdDate = 0), UserAction.APP_SETUP)

    @Test
    fun `a rolled-back transaction leaves no rule in memory, so the same folder can be added again`() {
        val service = emptyService()

        TransactionSynchronizationManager.initSynchronization()
        try {
            assertTrue(expose(service).isSuccessful)
            assertEquals("This file is not exposed.", service.isPathAllowed(FilePath.of("${root}srv/file")))
        } finally {
            // Rollback: the synchronizations are dropped without `afterCommit`
            TransactionSynchronizationManager.clearSynchronization()
        }

        assertTrue(expose(service).isSuccessful)
        assertNull(service.isPathAllowed(FilePath.of("${root}srv/file")))
    }

    @Test
    fun `a committed transaction adds the rule to memory`() {
        val service = emptyService()

        TransactionSynchronizationManager.initSynchronization()
        try {
            assertTrue(expose(service).isSuccessful)
            TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }

        assertNull(service.isPathAllowed(FilePath.of("${root}srv/file")))
    }

    // A junction on Windows (no Developer Mode needed), a symbolic link elsewhere
    private fun folderLink(link: Path, target: Path) = if (Platform.isWindows) junctionOrSkip(link, target) else symlinkOrSkip(link, target)

    @Test
    fun `a rule through a linked folder is refused with the real path`(@TempDir tempDir: Path) {
        val real = Files.createDirectories(tempDir.resolve("real").resolve("secret")).parent
        val link = tempDir.resolve("link")
        folderLink(link, real)
        val repository = mockk<FileVisibilityRepository>(relaxed = true)
        val state = State(env = emptyMap())
        val service = FileVisibilityService(repository, mockk(relaxed = true), state, SensitiveFolderPaths(state))

        val result = service.insertPath(FileVisibility(path = "$link/secret", isExposed = false, createdDate = 0), UserAction.ADD_FILE_VISIBILITY_CONFIGURATION)

        assertTrue(result.rejected)
        val realSecret = FilePath.of(real.toRealPath().resolve("secret").toString()).pathString
        assertEquals("This path goes through a link or a short name, so the rule would never match. Use the real path: $realSecret", result.error)
        verify(exactly = 0) { repository.insertOrReplace(any(), any(), any()) }

        // The real path, and the link itself (hides it in its folder), are fine
        assertNull(service.rulePathProblem(realSecret))
        assertNull(service.rulePathProblem(link.toString()))
        assertNull(service.rulePathProblem("$realSecret/not-made-yet"))
    }

    @Test
    fun `a hidden folder typed through a link is hidden at its real path`(@TempDir tempDir: Path) {
        val real = Files.createDirectories(tempDir.resolve("real").resolve("secret")).parent
        val link = tempDir.resolve("link")
        folderLink(link, real)
        // The temp folder may be on the sensitive list (AppData on Windows)
        val state = State(env = mapOf("FM_HIDDEN_FOLDER_PATHS" to "$link/secret", "FM_HIDE_SENSITIVE_FOLDERS" to "false"))
        val service = FileVisibilityService(mockk(relaxed = true), mockk(relaxed = true), state, SensitiveFolderPaths(state))
        service.loadHiddenFiles()
        service.insertPath(FileVisibility(path = root, isExposed = true, createdDate = 0), UserAction.APP_SETUP)

        assertEquals("This file is blocked.", service.isPathAllowed(FilePath.of(real.toRealPath().resolve("secret").resolve("file").toString())))
        assertNull(service.isPathAllowed(FilePath.of(real.toRealPath().resolve("public").toString())))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a rule through an 8 3 short name is refused`(@TempDir tempDir: Path) {
        val folder = Files.createDirectories(tempDir.resolve("Long folder name for 8.3"))
        val process = ProcessBuilder("cmd", "/c", "for %I in (\"$folder\") do @echo %~sI").redirectErrorStream(true).start()
        val short = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        assumeTrue(short.isNotEmpty() && !short.equals(folder.toString(), ignoreCase = true), "8.3 names are off on this drive")

        val problem = FileVisibilityService(mockk(relaxed = true), mockk(relaxed = true), State(env = emptyMap()), SensitiveFolderPaths(State(env = emptyMap())))
            .rulePathProblem("$short/inner")

        assertTrue(problem?.endsWith(FilePath.of(folder.toRealPath().resolve("inner").toString()).pathString) == true, problem)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `network folders and network drives can't be exposed`() {
        val repository = mockk<FileVisibilityRepository>(relaxed = true)
        val state = State(env = emptyMap())
        val service = FileVisibilityService(repository, mockk(relaxed = true), state, SensitiveFolderPaths(state))

        mockkObject(WindowsNative)
        try {
            // Z: stands for a mapped network drive
            every { WindowsNative.isOnNetworkDrive(any()) } answers { firstArg<Path>().root.toString().startsWith("Z:", ignoreCase = true) }
            every { WindowsNative.substTarget(any()) } returns null

            for (path in listOf("Z:/", "z:/data", "//host/share", "\\\\host\\share\\folder")) {
                val result = service.insertPath(FileVisibility(path = path, isExposed = true, createdDate = 0), UserAction.ADD_FILE_VISIBILITY_CONFIGURATION)
                assertTrue(result.rejected, path)
                assertEquals("Network drives and network folders (\\\\server\\share) aren't supported: $path", result.error)
            }
            verify(exactly = 0) { repository.insertOrReplace(any(), any(), any()) }

            assertTrue(service.insertPath(FileVisibility(path = root, isExposed = true, createdDate = 0), UserAction.ADD_FILE_VISIBILITY_CONFIGURATION).isSuccessful)
        } finally {
            unmockkObject(WindowsNative)
        }
    }
}
