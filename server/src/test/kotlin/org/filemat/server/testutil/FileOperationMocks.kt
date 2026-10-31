package org.filemat.server.testutil

import io.mockk.mockk
import org.filemat.server.common.State
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.file.ThumbnailService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.log.service.LogService
import java.nio.file.Path

/**
 * The collaborators of the move, copy and delete service tests. A test sets what it needs on them, for example
 * `every { mocks.fileService.isAllowedToDeleteFile(any(), any(), any()) } returns Result.ok()`.
 *
 * [dataFolder] is a temp folder, so the real data folder is never used. It is read when [state] is first used,
 * because a `@TempDir` field is set after the test class is made.
 */
class FileOperationMocks(dataFolder: () -> Path) {
    val fileService = mockk<FileService>()
    val entityService = mockk<EntityService>()
    val thumbnailService = mockk<ThumbnailService>(relaxed = true)
    val filesystemService = mockk<FilesystemService>(relaxed = true)
    val logService = mockk<LogService>(relaxed = true)
    val user = mockk<Principal>(relaxed = true)
    val fileLockService = FileLockService()
    val state by lazy { State(env = emptyMap(), dataFolderPath = dataFolder().toRealPath()) }
}
