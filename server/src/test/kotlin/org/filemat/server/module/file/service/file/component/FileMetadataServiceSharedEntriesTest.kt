package org.filemat.server.module.file.service.file.component

import io.mockk.every
import io.mockk.mockk
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.module.file.model.FileMetadata
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FileType
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.sharedFile.resolveSharedFilePath
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FileMetadataServiceSharedEntriesTest {

    @TempDir
    lateinit var tempDir: Path

    private val fileService = mockk<FileService>()
    private val filesystemService = mockk<FilesystemService>()
    private var previousFollowSymlinks = false

    private val service = FileMetadataService(
        fileService = fileService,
        filesystemService = filesystemService,
        savedFileService = mockk(relaxed = true),
        fileLockService = FileLockService(),
    )

    @BeforeEach
    fun setup() {
        previousFollowSymlinks = State.App.followSymlinks
    }

    @AfterEach
    fun tearDown() {
        State.App.followSymlinks = previousFollowSymlinks
    }

    @Test
    fun `shared listing keeps in-tree links and drops outbound targets`() {
        State.App.followSymlinks = true
        assumeTrue(State.App.followSymlinks, "followSymlinks is locked by environment variable")

        val shareRoot = Files.createDirectory(tempDir.resolve("share"))
        val nested = Files.createDirectory(shareRoot.resolve("nested"))
        Files.writeString(nested.resolve("hello.txt"), "from-nested")
        val outside = Files.createDirectory(tempDir.resolve("outside"))
        val secret = Files.writeString(outside.resolve("secret.txt"), "SHARED SECRET PAYLOAD")

        val inTree = shareRoot.resolve("in-tree")
        assumeTrue(runCatching { Files.createSymbolicLink(inTree, nested) }.isSuccess, "Could not create in-tree symlink")
        val escapeFile = shareRoot.resolve("escape-file")
        assumeTrue(runCatching { Files.createSymbolicLink(escapeFile, secret) }.isSuccess, "Could not create file symlink")

        mockShareResolve(shareRoot)
        every { filesystemService.getMetadata(any()) } returns folderMeta(shareRoot)
        every {
            fileService.getFolderEntries<FileMetadata>(any(), any(), any(), any(), any())
        } returns Result.ok(
            listOf(
                entryMeta(nested, FileType.FOLDER),
                entryMeta(inTree, FileType.FOLDER_LINK),
                entryMeta(escapeFile, FileType.FILE_LINK),
            )
        )

        val result = service.getSharedFileOrFolderEntries(FilePath.of("/"), shareToken = "share-token")

        assertTrue(result.isSuccessful)
        val names = result.value.second!!.map { Path.of(it.path).fileName.toString() }
        assertTrue(names.contains("nested"))
        assertTrue(names.contains("in-tree"))
        assertFalse(names.contains("escape-file"))
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

    private fun folderMeta(path: Path) = FileMetadata(
        path = path.toString(),
        modifiedDate = 0,
        createdDate = 0,
        fileType = FileType.FOLDER,
        size = 0,
        isExecutable = true,
        isWritable = true,
    )

    private fun entryMeta(path: Path, type: FileType) = FileMetadata(
        path = path.toString(),
        modifiedDate = 0,
        createdDate = 0,
        fileType = type,
        size = 0,
        isExecutable = false,
        isWritable = false,
    )
}
