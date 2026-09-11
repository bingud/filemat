package org.filemat.server.module.file.service.file.component

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FileType
import org.filemat.server.module.file.model.FullFileMetadata
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.sharedFile.resolveSharedFilePath
import org.filemat.server.module.user.model.UserAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FileEntryListsServiceSharedSearchTest {

    @TempDir
    lateinit var tempDir: Path

    private val fileService = mockk<FileService>()
    private val user = mockk<Principal>(relaxed = true)
    private var previousFollowSymlinks = false

    private val service = FileEntryListsService(
        fileLockService = FileLockService(),
        fileService = fileService,
        fileShareService = mockk(relaxed = true),
        entityService = mockk(relaxed = true),
        filesystemService = mockk(relaxed = true),
        savedFileService = mockk(relaxed = true),
        entityPermissionService = mockk(relaxed = true),
    )

    @BeforeEach
    fun setup() {
        previousFollowSymlinks = State.App.followSymlinks
        every { fileService.getFullMetadata(any(), any(), any(), any()) } answers {
            val path = invocation.args[1] as FilePath
            Result.ok(fileMeta(path.path))
        }
    }

    @AfterEach
    fun tearDown() {
        State.App.followSymlinks = previousFollowSymlinks
    }

    @Test
    fun `shared search follows in-tree links and skips outbound targets`() = runBlocking {
        State.App.followSymlinks = true
        assumeTrue(State.App.followSymlinks, "followSymlinks is locked by environment variable")

        val shareRoot = Files.createDirectory(tempDir.resolve("share"))
        val nested = Files.createDirectory(shareRoot.resolve("nested"))
        Files.writeString(nested.resolve("hello.txt"), "from-nested")
        val outside = Files.createDirectory(tempDir.resolve("outside"))
        val secret = Files.writeString(outside.resolve("secret.txt"), "SHARED SECRET PAYLOAD")

        assumeTrue(runCatching { Files.createSymbolicLink(shareRoot.resolve("in-tree"), nested) }.isSuccess, "Could not create in-tree symlink")
        assumeTrue(runCatching { Files.createSymbolicLink(shareRoot.resolve("escape-file"), secret) }.isSuccess, "Could not create file symlink")

        mockShareResolve(shareRoot)

        val hits = service.searchFiles(
            user = user,
            canonicalPath = FilePath.ofAlreadyNormalized(shareRoot),
            text = "hello",
            isShared = true,
            shareRelativePath = FilePath.of("/"),
            shareToken = "share-token",
            userAction = UserAction.SEARCH_FILE,
        ).toList().mapNotNull { it.valueOrNull?.path }

        assertTrue(hits.any { it.replace('\\', '/').endsWith("nested/hello.txt") })
        assertTrue(hits.any { it.replace('\\', '/').contains("in-tree") })

        val escapeHits = service.searchFiles(
            user = user,
            canonicalPath = FilePath.ofAlreadyNormalized(shareRoot),
            text = "escape",
            isShared = true,
            shareRelativePath = FilePath.of("/"),
            shareToken = "share-token",
            userAction = UserAction.SEARCH_FILE,
        ).toList().mapNotNull { it.valueOrNull?.path }

        assertFalse(escapeHits.any { it.contains("escape") })
        assertFalse(escapeHits.any { it.contains("secret") })
    }

    private fun mockShareResolve(shareRoot: Path) {
        every { fileService.resolvePathWithOptionalShare(any(), any()) } answers {
            val path = invocation.args[0] as FilePath
            resolveSharedFilePath(
                relativePath = path,
                shareRoot = FilePath.ofAlreadyNormalized(shareRoot),
            )
        }
        every { fileService.resolvePathWithOptionalShare(any(), any(), any()) } answers {
            val path = invocation.args[0] as FilePath
            resolveSharedFilePath(
                relativePath = path,
                shareRoot = FilePath.ofAlreadyNormalized(shareRoot),
            )
        }
    }

    private fun fileMeta(path: Path) = FullFileMetadata(
        path = path.toString(),
        modifiedDate = 0,
        createdDate = 0,
        fileType = FileType.FILE,
        size = 1,
        isExecutable = false,
        isWritable = false,
        permissions = emptyList(),
        isSaved = null,
    )
}
