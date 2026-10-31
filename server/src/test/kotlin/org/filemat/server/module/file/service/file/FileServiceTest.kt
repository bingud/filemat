package org.filemat.server.module.file.service.file

import com.github.f4b6a3.ulid.Ulid
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import org.filemat.server.common.model.Result
import org.filemat.server.common.State
import org.filemat.server.config.platform.PathPolicy
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FilesystemEntity
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.file.service.file.component.FileContentMetadataService
import org.filemat.server.module.file.service.file.component.FileContentService
import org.filemat.server.module.file.service.file.component.FileCopyService
import org.filemat.server.module.file.service.file.component.FileDeletionService
import org.filemat.server.module.file.service.file.component.FileEntryListsService
import org.filemat.server.module.file.service.file.component.FileFolderEntriesService
import org.filemat.server.module.file.service.file.component.FileMetadataService
import org.filemat.server.module.file.service.file.component.FileMoveService
import org.filemat.server.module.file.service.file.component.FileSecurityService
import org.filemat.server.testutil.assertNotFound
import org.filemat.server.testutil.assertSuccess
import org.filemat.server.testutil.symlinkOrSkip
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test

class FileServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val state = State(env = emptyMap())
    private val entityService = mockk<EntityService>()
    private val fileService = FileService(
        entityService = entityService,
        fileContentService = mockk<FileContentService>(relaxed = true),
        fileMoveService = mockk<FileMoveService>(relaxed = true),
        fileCopyService = mockk<FileCopyService>(relaxed = true),
        fileDeletionService = mockk<FileDeletionService>(relaxed = true),
        fileFolderEntriesService = mockk<FileFolderEntriesService>(relaxed = true),
        fileSecurityService = mockk<FileSecurityService>(relaxed = true),
        fileMetadataService = mockk<FileMetadataService>(relaxed = true),
        fileContentMetadataService = mockk<FileContentMetadataService>(relaxed = true),
        fileEntryListsService = mockk<FileEntryListsService>(relaxed = true),
        state = state,
    )


    @Test
    fun `shared resolve follows in-tree links and rejects outbound links`() {
        state.app.followSymlinks = true

        val shareRoot = Files.createDirectory(tempDir.resolve("share"))
        val nested = Files.createDirectory(shareRoot.resolve("nested"))
        val hello = Files.writeString(nested.resolve("hello.txt"), "hello")
        val outside = Files.createDirectory(tempDir.resolve("outside"))
        val secret = Files.writeString(outside.resolve("secret.txt"), "SECRET")

        symlinkOrSkip(shareRoot.resolve("in-tree"), nested)
        symlinkOrSkip(shareRoot.resolve("escape"), secret)

        every { entityService.getByShareToken(any(), any()) } returns Result.ok(
            FilesystemEntity(
                entityId = Ulid.fast(),
                path = shareRoot.toString(),
                fileKey = "posix:1:1",
                isFilesystemSupported = true,
                ownerId = null,
                pathKey = PathPolicy.pathKey(shareRoot.toString()),
            )
        )

        val inside = fileService.resolvePathWithOptionalShare(FilePath.of("in-tree/hello.txt"), "token")
        val outbound = fileService.resolvePathWithOptionalShare(FilePath.of("escape"), "token")

        assertSuccess(inside)
        assertTrue(hello.toRealPath() == inside.value.path)
        assertNotFound(outbound)
    }
}
