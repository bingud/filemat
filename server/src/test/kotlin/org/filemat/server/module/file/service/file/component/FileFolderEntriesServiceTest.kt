package org.filemat.server.module.file.service.file.component

import io.mockk.every
import org.filemat.server.common.model.Result
import org.filemat.server.module.file.model.FileMetadata
import org.filemat.server.module.file.model.FileType
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.testutil.assertSuccess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.common.State
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.testutil.assertRejected
import org.filemat.server.testutil.testPrincipal
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

class FileFolderEntriesServiceTest {

    private val fileService = mockk<FileService>()
    private val service = FileFolderEntriesService(
        fileService = fileService,
        fileVisibilityService = mockk(),
        savedFileService = mockk(),
        filesystemService = mockk(),
        logService = mockk(relaxed = true),
        fileLockService = FileLockService(),
        state = State(env = emptyMap()),
    )

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `the size of the drive list is never counted`() {
        // Every user may open the drive list, so counting it would let anyone walk every drive
        assertRejected(service.prepareFolderSize(testPrincipal(), FilePath.of("/")))
        verify(exactly = 0) { fileService.isAllowedToAccessFile(any(), any(), any()) }
    }

    @Test
    fun `a file that vanished after the folder was listed is left out`(@TempDir dir: Path) {
        val folder = dir.toRealPath()
        val kept = Files.writeString(folder.resolve("kept.txt"), "x")
        val gone = Files.writeString(folder.resolve("gone.tmp"), "x")
        val filesystemService = mockk<FilesystemService>()
        every { filesystemService.getMetadata(any()) } answers {
            val path = firstArg<FilePath>().path
            if (path == gone) null else FileMetadata(path.toString(), 0, 0, FileType.FILE, 1, isExecutable = false, isWritable = true)
        }
        every { fileService.isAllowedToAccessFile(any(), any(), any()) } returns Result.ok()
        val listing = FileFolderEntriesService(
            fileService = fileService,
            fileVisibilityService = mockk(),
            savedFileService = mockk(),
            filesystemService = filesystemService,
            logService = mockk(relaxed = true),
            fileLockService = FileLockService(),
            state = State(env = emptyMap()),
        )

        val result = listing.getFolderEntries(user = null, canonicalPath = FilePath.ofAlreadyNormalized(folder), ignorePermissions = true)

        assertSuccess(result)
        assertEquals(listOf(kept.toString()), result.value.map { it.path })
    }
}
