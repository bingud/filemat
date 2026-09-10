package org.filemat.server.module.file.service.filesystem.fileOperation

import io.mockk.every
import io.mockk.mockk
import org.filemat.server.common.model.Result
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.log.service.LogService
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FilesystemCopyServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val fileService = mockk<FileService>()
    private val logService = mockk<LogService>(relaxed = true)
    private val user = mockk<Principal>(relaxed = true)

    private val readable = mutableSetOf<Path>()

    private val service = FilesystemCopyService(
        logService = logService,
        fileService = fileService,
        fileLockService = FileLockService(),
    )

    @BeforeEach
    fun setup() {
        readable.clear()
        every { fileService.isAllowedToAccessFile(any(), any(), any()) } answers {
            val path = (invocation.args[1] as FilePath).path
            if (path in readable) Result.ok() else Result.reject("You do not have permission to access this file.")
        }
    }

    @Test
    fun `unread files do not create dest folder`() {
        val source = tempDir.resolve("folder")
        val destParent = tempDir.resolve("dest")
        val dest = destParent.resolve("folder")
        Files.createDirectories(source)
        Files.createDirectories(destParent)
        Files.writeString(source.resolve("file.txt"), "content")

        val result = copy(source, dest)

        assertTrue(result.isNotSuccessful)
        assertFalse(Files.exists(dest))
        assertTrue(Files.exists(source.resolve("file.txt")))
    }

    @Test
    fun `empty folder is created at dest`() {
        val source = tempDir.resolve("empty")
        val destParent = tempDir.resolve("dest")
        val dest = destParent.resolve("empty")
        Files.createDirectories(source)
        Files.createDirectories(destParent)

        val result = copy(source, dest)

        assertTrue(result.isSuccessful)
        assertTrue(Files.isDirectory(dest))
        assertTrue(Files.exists(source))
    }

    @Test
    fun `nested READ still creates dest path for allowed file`() {
        val source = tempDir.resolve("folder")
        val nested = source.resolve("nested")
        val destParent = tempDir.resolve("dest")
        val dest = destParent.resolve("folder")
        Files.createDirectories(nested)
        Files.createDirectories(destParent)
        Files.writeString(nested.resolve("allowed.txt"), "ok")
        Files.writeString(source.resolve("denied.txt"), "no")
        readable.add(nested.resolve("allowed.txt"))

        val result = copy(source, dest)

        assertTrue(result.isNotSuccessful)
        assertTrue(Files.exists(dest.resolve("nested").resolve("allowed.txt")))
        assertFalse(Files.exists(dest.resolve("denied.txt")))
        assertTrue(Files.exists(nested.resolve("allowed.txt")))
        assertTrue(Files.exists(source.resolve("denied.txt")))
    }

    @Test
    fun `denied nested folder does not create dest a b`() {
        val source = tempDir.resolve("folder")
        val nested = source.resolve("a").resolve("b")
        val destParent = tempDir.resolve("dest")
        val dest = destParent.resolve("folder")
        Files.createDirectories(nested)
        Files.createDirectories(destParent)
        Files.writeString(nested.resolve("secret.txt"), "no")

        val result = copy(source, dest)

        assertTrue(result.isNotSuccessful)
        assertFalse(Files.exists(dest))
        assertFalse(Files.exists(dest.resolve("a")))
        assertFalse(Files.exists(dest.resolve("a").resolve("b")))
        assertTrue(Files.exists(nested.resolve("secret.txt")))
    }

    @Test
    fun `denied sibling folder is not created when another file copies`() {
        val source = tempDir.resolve("folder")
        val deniedNested = source.resolve("a").resolve("b")
        val destParent = tempDir.resolve("dest")
        val dest = destParent.resolve("folder")
        Files.createDirectories(deniedNested)
        Files.createDirectories(destParent)
        Files.writeString(deniedNested.resolve("secret.txt"), "no")
        Files.writeString(source.resolve("ok.txt"), "ok")
        readable.add(source.resolve("ok.txt"))

        val result = copy(source, dest)

        assertTrue(result.isNotSuccessful)
        assertTrue(Files.exists(dest.resolve("ok.txt")))
        assertFalse(Files.exists(dest.resolve("a")))
    }

    private fun copy(source: Path, dest: Path) = service.copyFile(
        canonicalSource = FilePath.ofAlreadyNormalized(source),
        canonicalDestination = FilePath.ofAlreadyNormalized(dest),
        user = user,
        ignorePermissions = false,
        copyResolvedSymlinks = false,
    )
}
