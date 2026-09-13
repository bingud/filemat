package org.filemat.server.module.file.service.file

import com.github.f4b6a3.ulid.Ulid
import io.mockk.every
import io.mockk.mockk
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FilesystemEntity
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.file.service.file.component.FileContentService
import org.filemat.server.module.file.service.file.component.FileCopyService
import org.filemat.server.module.file.service.file.component.FileDeletionService
import org.filemat.server.module.file.service.file.component.FileEntryListsService
import org.filemat.server.module.file.service.file.component.FileFolderEntriesService
import org.filemat.server.module.file.service.file.component.FileMetadataService
import org.filemat.server.module.file.service.file.component.FileMoveService
import org.filemat.server.module.file.service.file.component.FileSecurityService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FileServiceResolveShareTest {

    @TempDir
    lateinit var tempDir: Path

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
        fileEntryListsService = mockk<FileEntryListsService>(relaxed = true),
    )

    private var previousFollowSymlinks = false

    @BeforeEach
    fun setup() {
        previousFollowSymlinks = State.App.followSymlinks
    }

    @AfterEach
    fun tearDown() {
        State.App.followSymlinks = previousFollowSymlinks
    }

    @Test
    fun `shared resolve follows in-tree links and rejects outbound links`() {
        State.App.followSymlinks = true
        assumeTrue(State.App.followSymlinks, "followSymlinks is locked by environment variable")

        val shareRoot = Files.createDirectory(tempDir.resolve("share"))
        val nested = Files.createDirectory(shareRoot.resolve("nested"))
        val hello = Files.writeString(nested.resolve("hello.txt"), "hello")
        val outside = Files.createDirectory(tempDir.resolve("outside"))
        val secret = Files.writeString(outside.resolve("secret.txt"), "SECRET")

        assumeTrue(runCatching { Files.createSymbolicLink(shareRoot.resolve("in-tree"), nested) }.isSuccess, "Could not create in-tree symlink")
        assumeTrue(runCatching { Files.createSymbolicLink(shareRoot.resolve("escape"), secret) }.isSuccess, "Could not create outbound symlink")

        every { entityService.getByShareToken(any(), any()) } returns Result.ok(
            FilesystemEntity(
                entityId = Ulid.fast(),
                path = shareRoot.toString(),
                inode = 1L,
                isFilesystemSupported = true,
                ownerId = null,
            )
        )

        val inside = fileService.resolvePathWithOptionalShare(FilePath.of("in-tree/hello.txt"), "token")
        val outbound = fileService.resolvePathWithOptionalShare(FilePath.of("escape"), "token")

        assertTrue(inside.isSuccessful)
        assertTrue(hello.toRealPath() == inside.value.path)
        assertTrue(outbound.notFound)
    }
}
