package org.filemat.server.module.file.service.file.component

import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.common.State
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.testutil.assertRejected
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FileCopyServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val fileService = mockk<FileService>()
    private val filesystemService = mockk<FilesystemService>()
    private val service = FileCopyService(fileService, filesystemService, State(env = emptyMap()))

    @Test
    fun `a copy to a root folder is refused instead of failing`() {
        val file = Files.writeString(tempDir.toRealPath().resolve("a.txt"), "x")
        val root = FilePath.ofAlreadyNormalized(file.root)

        assertRejected(service.copyFile(mockk<Principal>(relaxed = true), FilePath.ofAlreadyNormalized(file), root))

        verify(exactly = 0) { filesystemService.copyFile(any(), any(), any(), any(), any()) }
    }
}
