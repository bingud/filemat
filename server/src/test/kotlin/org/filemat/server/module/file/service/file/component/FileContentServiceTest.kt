package org.filemat.server.module.file.service.file.component

import io.mockk.every
import io.mockk.mockk
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.sharedFile.resolveSharedFilePath
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FileContentServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val fileService = mockk<FileService>()
    private val user = mockk<Principal>(relaxed = true)
    private val readable = mutableSetOf<Path>()
    private var previousFollowSymlinks = false

    private val service = FileContentService(
        fileService = fileService,
        fileLockService = FileLockService(),
        filesystemService = mockk<FilesystemService>(relaxed = true),
    )

    @BeforeEach
    fun setup() {
        readable.clear()
        previousFollowSymlinks = State.App.followSymlinks
        every { fileService.resolvePathWithOptionalShare(any(), any()) } answers {
            Result.ok(invocation.args[0] as FilePath)
        }
        every { fileService.resolvePathWithOptionalShare(any(), any(), any()) } answers {
            Result.ok(invocation.args[0] as FilePath)
        }
        every { fileService.isAllowedToAccessFile(any(), any(), any()) } answers {
            val path = (invocation.args[1] as FilePath).path
            if (isReadable(path)) Result.ok() else Result.reject("You do not have permission to access this file.")
        }
    }

    @AfterEach
    fun tearDown() {
        State.App.followSymlinks = previousFollowSymlinks
    }

    @Test
    fun `zips a regular readable file`() {
        State.App.followSymlinks = false
        val folder = Files.createDirectory(tempDir.resolve("folder"))
        val file = Files.writeString(folder.resolve("a.txt"), "hello")
        allow(folder)
        allow(file)

        val entries = zipEntries(folder)

        assertEquals("hello", entries["folder/a.txt"])
    }

    @Test
    fun `dir symlink children are authorized on the real path`() {
        State.App.followSymlinks = true
        assumeFollowSymlinksHonored()

        val allowed = Files.createDirectory(tempDir.resolve("allowed"))
        val denied = Files.createDirectory(tempDir.resolve("denied"))
        val visible = Files.writeString(allowed.resolve("visible.txt"), "ok")
        val secret = Files.writeString(denied.resolve("secret.txt"), "DENIED SECRET PAYLOAD")
        createDirectoryLink(allowed.resolve("escape-to-denied-dir"), denied)

        allow(allowed)
        allow(visible)

        val entries = zipEntries(allowed)

        assertEquals("ok", entries["allowed/visible.txt"])
        assertFalse(entries.values.any { it.contains("DENIED SECRET PAYLOAD") })
        assertFalse(entries.keys.any { it.endsWith("secret.txt") })
        assertTrue(Files.exists(secret))
    }

    @Test
    fun `dir symlink children are included when the real path is allowed`() {
        State.App.followSymlinks = true
        assumeFollowSymlinksHonored()

        val allowed = Files.createDirectory(tempDir.resolve("allowed"))
        val other = Files.createDirectory(tempDir.resolve("other"))
        val visible = Files.writeString(allowed.resolve("visible.txt"), "ok")
        val shared = Files.writeString(other.resolve("shared.txt"), "from-other")
        createDirectoryLink(allowed.resolve("to-other"), other)

        allow(allowed)
        allow(visible)
        allow(other)
        allow(shared)

        val entries = zipEntries(allowed)

        assertEquals("ok", entries["allowed/visible.txt"])
        assertEquals("from-other", entries["allowed/to-other/shared.txt"])
    }

    @Test
    fun `file symlink is authorized on the real path`() {
        State.App.followSymlinks = true
        assumeFollowSymlinksHonored()

        val allowed = Files.createDirectory(tempDir.resolve("allowed"))
        val denied = Files.createDirectory(tempDir.resolve("denied"))
        val secret = Files.writeString(denied.resolve("secret.txt"), "DENIED SECRET PAYLOAD")
        val link = allowed.resolve("escape-file")
        assumeTrue(runCatching { Files.createSymbolicLink(link, secret) }.isSuccess, "Could not create file symlink")

        allow(allowed)

        val entries = zipEntries(allowed)

        assertFalse(entries.values.any { it.contains("DENIED SECRET PAYLOAD") })
        assertFalse(entries.containsKey("allowed/escape-file"))
    }

    @Test
    fun `shared zip includes in-tree symlink content and skips outbound targets`() {
        State.App.followSymlinks = true
        assumeFollowSymlinksHonored()

        val shareRoot = Files.createDirectory(tempDir.resolve("share"))
        val nested = Files.createDirectory(shareRoot.resolve("nested"))
        val visible = Files.writeString(nested.resolve("hello.txt"), "from-nested")
        val outside = Files.createDirectory(tempDir.resolve("outside"))
        val secret = Files.writeString(outside.resolve("secret.txt"), "SHARED SECRET PAYLOAD")

        createDirectoryLink(shareRoot.resolve("in-tree"), nested)
        assumeTrue(runCatching { Files.createSymbolicLink(shareRoot.resolve("escape-file"), secret) }.isSuccess, "Could not create file symlink")
        createDirectoryLink(shareRoot.resolve("escape-dir"), outside)

        allow(shareRoot)
        allow(nested)
        allow(visible)
        allow(outside)
        allow(secret)

        mockShareResolve(shareRoot)

        val entries = zipEntries(FilePath.of("/"), shareToken = "share-token", zipRootName = shareRoot.fileName)

        assertEquals("from-nested", entries["share/nested/hello.txt"])
        assertEquals("from-nested", entries["share/in-tree/hello.txt"])
        assertFalse(entries.values.any { it.contains("SHARED SECRET PAYLOAD") })
        assertFalse(entries.keys.any { it.endsWith("secret.txt") })
        assertFalse(entries.containsKey("share/escape-file"))
    }

    @Test
    fun `shared zip skips bounce-back symlink that leaves the share`() {
        State.App.followSymlinks = true
        assumeFollowSymlinksHonored()

        val shareRoot = Files.createDirectory(tempDir.resolve("share"))
        val visible = Files.writeString(shareRoot.resolve("inside.txt"), "inside")
        val outside = Files.createDirectory(tempDir.resolve("outside"))
        val bounce = outside.resolve("bounce")
        assumeTrue(runCatching { Files.createSymbolicLink(bounce, visible) }.isSuccess, "Could not create bounce target")
        assumeTrue(runCatching { Files.createSymbolicLink(shareRoot.resolve("leave-and-return"), bounce) }.isSuccess, "Could not create bounce-back symlink")

        allow(shareRoot)
        allow(visible)
        allow(outside)
        allow(bounce)

        mockShareResolve(shareRoot)

        val entries = zipEntries(FilePath.of("/"), shareToken = "share-token", zipRootName = shareRoot.fileName)

        assertEquals("inside", entries["share/inside.txt"])
        assertFalse(entries.containsKey("share/leave-and-return"))
    }

    private fun mockShareResolve(shareRoot: Path) {
        every { fileService.resolvePathWithOptionalShare(any(), any()) } answers {
            val path = invocation.args[0] as FilePath
            val token = invocation.args[1] as String?
            if (token == null) {
                Result.ok(path)
            } else {
                resolveSharedFilePath(
                    relativePath = path,
                    shareRoot = FilePath.ofAlreadyNormalized(shareRoot),
                )
            }
        }
        every { fileService.resolvePathWithOptionalShare(any(), any(), any()) } answers {
            val path = invocation.args[0] as FilePath
            val token = invocation.args[1] as String?
            if (token == null) {
                Result.ok(path)
            } else {
                resolveSharedFilePath(
                    relativePath = path,
                    shareRoot = FilePath.ofAlreadyNormalized(shareRoot),
                )
            }
        }
    }

    private fun allow(path: Path) {
        readable.add(path)
        readable.add(path.toRealPath())
    }

    private fun isReadable(path: Path): Boolean {
        if (path in readable) return true
        val real = runCatching { path.toRealPath() }.getOrNull() ?: return false
        return real in readable
    }

    private fun zipEntries(path: Path): Map<String, String> {
        return zipEntries(FilePath.ofAlreadyNormalized(path), shareToken = null, zipRootName = path.fileName)
    }

    private fun zipEntries(path: FilePath, shareToken: String?, zipRootName: Path?): Map<String, String> {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            service.addFileToZip(
                zip = zip,
                rawPath = path,
                existingBaseZipPath = zipRootName,
                principal = user,
                shareToken = shareToken,
            )
        }

        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                entries[entry.name.replace('\\', '/')] = zip.readBytes().decodeToString()
            }
        }
        return entries
    }

    private fun assumeFollowSymlinksHonored() {
        assumeTrue(State.App.followSymlinks, "followSymlinks is locked by environment variable")
    }

    private fun createDirectoryLink(link: Path, target: Path) {
        try {
            Files.createSymbolicLink(link, target)
            return
        } catch (_: Exception) {
        }

        val process = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toAbsolutePath().toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assumeTrue(process.waitFor() == 0 && Files.exists(link), "Could not create directory link: $output")
    }
}
