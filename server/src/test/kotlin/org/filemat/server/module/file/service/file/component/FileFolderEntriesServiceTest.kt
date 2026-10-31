package org.filemat.server.module.file.service.file.component

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
}
