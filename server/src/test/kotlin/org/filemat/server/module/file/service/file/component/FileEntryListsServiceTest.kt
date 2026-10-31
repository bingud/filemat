package org.filemat.server.module.file.service.file.component

import org.filemat.server.testutil.symlinkOrSkip
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
import io.mockk.verify
import org.filemat.server.testutil.assertRejected
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class FileEntryListsServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val state = State(env = emptyMap())
    private val fileService = mockk<FileService>()
    private val user = mockk<Principal>(relaxed = true)

    private val service = FileEntryListsService(
        fileLockService = FileLockService(),
        fileService = fileService,
        fileShareService = mockk(relaxed = true),
        entityService = mockk(relaxed = true),
        filesystemService = mockk(relaxed = true),
        savedFileService = mockk(relaxed = true),
        entityPermissionService = mockk(relaxed = true),
        state = state,
    )

    @BeforeEach
    fun setup() {
        every { fileService.isAllowedToAccessFile(any(), any(), any()) } returns Result.ok()
        every { fileService.getFullMetadata(any(), any(), any(), any()) } answers {
            val path = invocation.args[1] as FilePath
            Result.ok(fileMeta(path.path))
        }
    }

    private fun search(folder: FilePath) = service.searchFiles(
        user = user,
        canonicalPath = folder,
        text = "hello",
        userAction = UserAction.SEARCH_FILE,
    )

    @Test
    fun `a folder the user can't open is not searched`() {
        val folder = Files.createDirectory(tempDir.resolve("private"))
        Files.writeString(folder.resolve("hello.txt"), "x")
        every { fileService.isAllowedToAccessFile(user, FilePath.ofAlreadyNormalized(folder), null) } returns Result.reject("This file is not exposed.")

        val result = search(FilePath.ofAlreadyNormalized(folder))

        assertRejected(result)
        verify(exactly = 0) { fileService.getFullMetadata(any(), any(), any(), any()) }
    }

    @Test
    fun `a folder the user can open is searched`() = runBlocking {
        val folder = Files.createDirectory(tempDir.resolve("open"))
        Files.writeString(folder.resolve("hello.txt"), "x")

        val hits = search(FilePath.ofAlreadyNormalized(folder)).value.toList()

        assertEquals(1, hits.size)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `the drive list is never searched`() {
        assertRejected(search(FilePath.of("/")))
        verify(exactly = 0) { fileService.isAllowedToAccessFile(any(), any(), any()) }
    }

    @Test
    fun `shared search does not follow directory links and skips outbound targets`() = runBlocking {
        state.app.followSymlinks = true

        val shareRoot = Files.createDirectory(tempDir.resolve("share"))
        val nested = Files.createDirectory(shareRoot.resolve("nested"))
        Files.writeString(nested.resolve("hello.txt"), "from-nested")
        val outside = Files.createDirectory(tempDir.resolve("outside"))
        val secret = Files.writeString(outside.resolve("secret.txt"), "SHARED SECRET PAYLOAD")

        symlinkOrSkip(shareRoot.resolve("in-tree"), nested)
        symlinkOrSkip(shareRoot.resolve("escape-file"), secret)

        mockShareResolve(shareRoot)

        val hits = service.searchFiles(
            user = user,
            canonicalPath = FilePath.ofAlreadyNormalized(shareRoot),
            text = "hello",
            isShared = true,
            shareRelativePath = FilePath.of("/"),
            shareToken = "share-token",
            userAction = UserAction.SEARCH_FILE,
        ).value.toList().mapNotNull { it.valueOrNull?.path }

        assertTrue(hits.any { it.replace('\\', '/').endsWith("nested/hello.txt") })
        assertFalse(hits.any { it.replace('\\', '/').contains("in-tree") })

        val escapeHits = service.searchFiles(
            user = user,
            canonicalPath = FilePath.ofAlreadyNormalized(shareRoot),
            text = "escape",
            isShared = true,
            shareRelativePath = FilePath.of("/"),
            shareToken = "share-token",
            userAction = UserAction.SEARCH_FILE,
        ).value.toList().mapNotNull { it.valueOrNull?.path }

        assertFalse(escapeHits.any { it.contains("escape") })
        assertFalse(escapeHits.any { it.contains("secret") })
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `shared search does not recurse forever through a directory link to an ancestor`() = runBlocking {
        state.app.followSymlinks = true

        val shareRoot = Files.createDirectory(tempDir.resolve("share"))
        Files.writeString(shareRoot.resolve("hello.txt"), "from-root")
        symlinkOrSkip(shareRoot.resolve("loop"), shareRoot)

        mockShareResolve(shareRoot)

        val hits = service.searchFiles(
            user = user,
            canonicalPath = FilePath.ofAlreadyNormalized(shareRoot),
            text = "hello",
            isShared = true,
            shareRelativePath = FilePath.of("/"),
            shareToken = "share-token",
            userAction = UserAction.SEARCH_FILE,
        ).value.toList().mapNotNull { it.valueOrNull?.path }

        assertTrue(hits.any { it.replace('\\', '/').endsWith("hello.txt") })
        assertFalse(hits.any { it.replace('\\', '/').contains("loop/") })
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `shared search does not recurse forever through sibling directory links`() = runBlocking {
        state.app.followSymlinks = true

        val shareRoot = Files.createDirectory(tempDir.resolve("share"))
        val a = Files.createDirectory(shareRoot.resolve("a"))
        val b = Files.createDirectory(shareRoot.resolve("b"))
        Files.writeString(a.resolve("hello.txt"), "from-a")
        symlinkOrSkip(a.resolve("to-b"), b)
        symlinkOrSkip(b.resolve("to-a"), a)

        mockShareResolve(shareRoot)

        val hits = service.searchFiles(
            user = user,
            canonicalPath = FilePath.ofAlreadyNormalized(shareRoot),
            text = "hello",
            isShared = true,
            shareRelativePath = FilePath.of("/"),
            shareToken = "share-token",
            userAction = UserAction.SEARCH_FILE,
        ).value.toList().mapNotNull { it.valueOrNull?.path }

        assertTrue(hits.any { it.replace('\\', '/').endsWith("hello.txt") })
    }

    private fun mockShareResolve(shareRoot: Path) {
        every { fileService.resolvePathWithOptionalShare(any(), any()) } answers {
            val path = invocation.args[0] as FilePath
            resolveSharedFilePath(
                relativePath = path,
                shareRoot = FilePath.ofAlreadyNormalized(shareRoot),
                followSymlinks = state.app.followSymlinks,
            )
        }
        every { fileService.resolvePathWithOptionalShare(any(), any(), any()) } answers {
            val path = invocation.args[0] as FilePath
            resolveSharedFilePath(
                relativePath = path,
                shareRoot = FilePath.ofAlreadyNormalized(shareRoot),
                followSymlinks = state.app.followSymlinks,
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
