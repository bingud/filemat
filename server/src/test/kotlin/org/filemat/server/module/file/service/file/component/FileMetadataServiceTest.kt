package org.filemat.server.module.file.service.file.component

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.nio.file.Files
import java.nio.file.Path
import org.filemat.server.common.model.Result
import org.filemat.server.common.State
import org.filemat.server.config.platform.WindowsNative
import org.filemat.server.module.file.model.FileMetadata
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FileType
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.sharedFile.resolveSharedFilePath
import org.filemat.server.testutil.assertSuccess
import org.filemat.server.testutil.junctionOrSkip
import org.filemat.server.testutil.symlinkOrSkip
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test

class FileMetadataServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val state = State(env = emptyMap())
    private val fileService = mockk<FileService>()
    private val filesystemService = mockk<FilesystemService>()

    private val service = FileMetadataService(
        fileService = fileService,
        filesystemService = filesystemService,
        savedFileService = mockk(relaxed = true),
        fileLockService = FileLockService(),
        state = state,
    )

    @Test
    fun `shared listing keeps in-tree links and drops outbound targets`() {
        state.app.followSymlinks = true

        val shareRoot = Files.createDirectory(tempDir.resolve("share"))
        val nested = Files.createDirectory(shareRoot.resolve("nested"))
        Files.writeString(nested.resolve("hello.txt"), "from-nested")
        val outside = Files.createDirectory(tempDir.resolve("outside"))
        val secret = Files.writeString(outside.resolve("secret.txt"), "SHARED SECRET PAYLOAD")

        val inTree = shareRoot.resolve("in-tree")
        symlinkOrSkip(inTree, nested)
        val escapeFile = shareRoot.resolve("escape-file")
        symlinkOrSkip(escapeFile, secret)

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

        assertSuccess(result)
        val names = result.value.second!!.map { Path.of(it.path).fileName.toString() }
        assertTrue(names.contains("nested"))
        assertTrue(names.contains("in-tree"))
        assertFalse(names.contains("escape-file"))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a path in the wrong case gets the real case in its metadata on Windows`() {
        state.app.followSymlinks = false
        Files.writeString(tempDir.resolve("Report.txt"), "hello")

        every { fileService.isAllowedToAccessFile(any(), any(), any()) } returns Result.ok()
        every { filesystemService.getMetadata(any()) } answers { entryMeta((invocation.args[0] as FilePath).path, FileType.FILE) }

        val result = service.getMetadata(user = null, rawPath = FilePath.of(tempDir.resolve("report.TXT").toString()))

        assertSuccess(result)
        assertEquals("Report.txt", Path.of(result.value.path).fileName.toString())
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a path on a subst drive gets the real path in its metadata on Windows`() {
        state.app.followSymlinks = false
        val folder = tempDir.toRealPath()
        val file = Files.writeString(folder.resolve("a.txt"), "hello")

        withSubst("Q:" to folder.toString()) {
            val result = service.getMetadata(user = null, rawPath = FilePath.of("Q:/a.txt"))

            assertSuccess(result)
            assertEquals(file.toString(), Path.of(result.value.path).toString())
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a file behind a followed junction on a subst drive keeps the junction name, on the real drive`() {
        state.app.followSymlinks = true
        val folder = tempDir.toRealPath()
        val target = Files.createDirectory(folder.resolve("target"))
        Files.writeString(target.resolve("a.txt"), "hello")
        val junction = folder.resolve("link")
        junctionOrSkip(junction, target)

        withSubst("Q:" to folder.toString()) {
            val result = service.getMetadata(user = null, rawPath = FilePath.of("Q:/link/a.txt"))

            assertSuccess(result)
            assertEquals(junction.resolve("a.txt").toString(), Path.of(result.value.path).toString())
        }
    }

    private fun withSubst(vararg targets: Pair<String, String>, block: () -> Unit) {
        mockkObject(WindowsNative)
        try {
            every { WindowsNative.isOnNetworkDrive(any()) } returns false
            every { WindowsNative.substTarget(any()) } answers { targets.toMap()[firstArg<String>().uppercase()] }
            every { fileService.isAllowedToAccessFile(any(), any(), any()) } returns Result.ok()
            every { filesystemService.getMetadata(any()) } answers { entryMeta((invocation.args[0] as FilePath).path, FileType.FILE) }
            block()
        } finally {
            unmockkObject(WindowsNative)
        }
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
