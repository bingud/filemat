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
import org.filemat.server.testutil.directoryLinkOrSkip
import org.filemat.server.testutil.setReadOnly
import org.filemat.server.testutil.symlinkOrSkip
import org.filemat.server.testutil.withFileLocked
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FileContentServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val state = State(env = emptyMap())
    private val fileService = mockk<FileService>()
    private val user = mockk<Principal>(relaxed = true)
    private val readable = mutableSetOf<Path>()
    private val filesystemService = mockk<FilesystemService>(relaxed = true)

    private val service = FileContentService(
        fileService = fileService,
        fileLockService = FileLockService(),
        filesystemService = filesystemService,
        logService = mockk(relaxed = true),
        state = state,
    )

    @BeforeEach
    fun setup() {
        readable.clear()
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

    @Test
    fun `file content is read for a regular file`() {
        state.app.followSymlinks = false
        val file = Files.writeString(tempDir.resolve("a.txt"), "hello")
        allow(file)

        val result = service.getFileContent(user, FilePath.ofAlreadyNormalized(file))

        assertTrue(result.isSuccessful)
        assertEquals("hello", result.value.use { String(it.readAllBytes()) })
    }

    @Test
    fun `file content of a folder is not found`() {
        state.app.followSymlinks = false
        val folder = Files.createDirectory(tempDir.resolve("folder"))
        allow(folder)

        assertTrue(service.getFileContent(user, FilePath.ofAlreadyNormalized(folder)).notFound)
    }

    @Test
    fun `zips a regular readable file`() {
        state.app.followSymlinks = false
        val folder = Files.createDirectory(tempDir.resolve("folder"))
        val file = Files.writeString(folder.resolve("a.txt"), "hello")
        allow(folder)
        allow(file)

        val entries = zipEntries(folder)

        assertEquals("hello", entries["folder/a.txt"])
    }

    @Test
    fun `zip entry names use forward slashes`() {
        state.app.followSymlinks = false
        val folder = Files.createDirectory(tempDir.resolve("folder"))
        val nested = Files.createDirectory(folder.resolve("nested"))
        val file = Files.writeString(nested.resolve("a.txt"), "hello")
        allow(folder)
        allow(nested)
        allow(file)

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
            service.addFileToZip(
                zip = zip,
                rawPath = FilePath.ofAlreadyNormalized(folder),
                existingBaseZipPath = folder.fileName,
                principal = user,
                shareToken = null,
            )
        }

        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertFalse(entry.name.contains('\\'), "zip entry used backslash: ${entry.name}")
            }
        }
    }

    @Test
    fun `uncompressed zip round trips a multi megabyte file`() {
        state.app.followSymlinks = false
        val folder = Files.createDirectory(tempDir.resolve("folder"))
        val payload = ByteArray(3 * 1024 * 1024) { i -> (i % 251).toByte() }
        val file = folder.resolve("big.bin")
        Files.write(file, payload)
        allow(folder)
        allow(file)

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
            service.addFileToZip(
                zip = zip,
                rawPath = FilePath.ofAlreadyNormalized(folder),
                existingBaseZipPath = folder.fileName,
                principal = user,
                shareToken = null,
            )
        }

        val bytes = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                bytes[entry.name.replace('\\', '/')] = zip.readBytes()
            }
        }

        org.junit.jupiter.api.Assertions.assertArrayEquals(payload, bytes["folder/big.bin"])
    }

    @Test
    fun `dir symlink children are authorized on the real path`() {
        state.app.followSymlinks = true

        val allowed = Files.createDirectory(tempDir.resolve("allowed"))
        val denied = Files.createDirectory(tempDir.resolve("denied"))
        val visible = Files.writeString(allowed.resolve("visible.txt"), "ok")
        val secret = Files.writeString(denied.resolve("secret.txt"), "DENIED SECRET PAYLOAD")
        directoryLinkOrSkip(allowed.resolve("escape-to-denied-dir"), denied)

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
        state.app.followSymlinks = true

        val allowed = Files.createDirectory(tempDir.resolve("allowed"))
        val other = Files.createDirectory(tempDir.resolve("other"))
        val visible = Files.writeString(allowed.resolve("visible.txt"), "ok")
        val shared = Files.writeString(other.resolve("shared.txt"), "from-other")
        directoryLinkOrSkip(allowed.resolve("to-other"), other)

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
        state.app.followSymlinks = true

        val allowed = Files.createDirectory(tempDir.resolve("allowed"))
        val denied = Files.createDirectory(tempDir.resolve("denied"))
        val secret = Files.writeString(denied.resolve("secret.txt"), "DENIED SECRET PAYLOAD")
        val link = allowed.resolve("escape-file")
        symlinkOrSkip(link, secret)

        allow(allowed)

        val entries = zipEntries(allowed)

        assertFalse(entries.values.any { it.contains("DENIED SECRET PAYLOAD") })
        assertFalse(entries.containsKey("allowed/escape-file"))
    }

    @Test
    fun `shared zip includes in-tree symlink content and skips outbound targets`() {
        state.app.followSymlinks = true

        val shareRoot = Files.createDirectory(tempDir.resolve("share"))
        val nested = Files.createDirectory(shareRoot.resolve("nested"))
        val visible = Files.writeString(nested.resolve("hello.txt"), "from-nested")
        val outside = Files.createDirectory(tempDir.resolve("outside"))
        val secret = Files.writeString(outside.resolve("secret.txt"), "SHARED SECRET PAYLOAD")

        directoryLinkOrSkip(shareRoot.resolve("in-tree"), nested)
        symlinkOrSkip(shareRoot.resolve("escape-file"), secret)
        directoryLinkOrSkip(shareRoot.resolve("escape-dir"), outside)

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
        state.app.followSymlinks = true

        val shareRoot = Files.createDirectory(tempDir.resolve("share"))
        val visible = Files.writeString(shareRoot.resolve("inside.txt"), "inside")
        val outside = Files.createDirectory(tempDir.resolve("outside"))
        val bounce = outside.resolve("bounce")
        symlinkOrSkip(bounce, visible)
        symlinkOrSkip(shareRoot.resolve("leave-and-return"), bounce)

        allow(shareRoot)
        allow(visible)
        allow(outside)
        allow(bounce)

        mockShareResolve(shareRoot)

        val entries = zipEntries(FilePath.of("/"), shareToken = "share-token", zipRootName = shareRoot.fileName)

        assertEquals("inside", entries["share/inside.txt"])
        assertFalse(entries.containsKey("share/leave-and-return"))
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `zip does not recurse forever through a directory link to an ancestor`() {
        state.app.followSymlinks = true

        val folder = Files.createDirectory(tempDir.resolve("folder"))
        val hello = Files.writeString(folder.resolve("hello.txt"), "hi")
        directoryLinkOrSkip(folder.resolve("loop"), folder)

        allow(folder)
        allow(hello)

        val entries = zipEntries(folder)

        assertEquals("hi", entries["folder/hello.txt"])
        assertFalse(entries.keys.any { it.replace('\\', '/').contains("/loop/") })
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `zip does not recurse forever through sibling directory links`() {
        state.app.followSymlinks = true

        val folder = Files.createDirectory(tempDir.resolve("folder"))
        val a = Files.createDirectory(folder.resolve("a"))
        val b = Files.createDirectory(folder.resolve("b"))
        val hello = Files.writeString(a.resolve("hello.txt"), "from-a")
        directoryLinkOrSkip(a.resolve("to-b"), b)
        directoryLinkOrSkip(b.resolve("to-a"), a)

        allow(folder)
        allow(a)
        allow(b)
        allow(hello)

        val entries = zipEntries(folder)

        assertTrue(entries.values.any { it == "from-a" })
        assertTrue(entries.keys.any { it.replace('\\', '/').endsWith("hello.txt") })
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
                    followSymlinks = state.app.followSymlinks,
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
                    followSymlinks = state.app.followSymlinks,
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

    private fun edit(file: Path, content: String): Result<FileContentService.EditFileResult> {
        every { fileService.isAllowedToEditFile(any(), any(), any()) } returns Result.ok()
        every { filesystemService.getSize(any()) } answers { Result.ok(Files.size((invocation.args[0] as FilePath).path)) }
        return service.editFile(user, FilePath.ofAlreadyNormalized(file), content)
    }

    @Test
    fun `editing replaces the whole content`() {
        val file = Files.writeString(tempDir.resolve("a.txt"), "a longer old text")

        assertTrue(edit(file, "new").isSuccessful)

        assertEquals("new", Files.readString(file))
    }

    @Test
    fun `editing to a longer text replaces the whole content`() {
        val file = Files.writeString(tempDir.resolve("a.txt"), "old")

        assertTrue(edit(file, "a much longer new text").isSuccessful)

        assertEquals("a much longer new text", Files.readString(file))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `editing a file that another program has locked part of is a conflict and keeps its content`() {
        val old = "0123456789".repeat(10)
        val file = Files.writeString(tempDir.resolve("a.txt"), old)
        // Bytes 50-59 locked by another process: a write would stop there, with the start already new
        val locker = ProcessBuilder(
            "powershell", "-NoProfile", "-Command",
            "\$f = [IO.File]::Open('$file', 'Open', 'ReadWrite', 'ReadWrite'); \$f.Lock(50, 10); 'locked'; Start-Sleep 60",
        ).redirectErrorStream(true).start()
        val result = try {
            assertEquals("locked", locker.inputStream.bufferedReader().readLine()?.trim())
            edit(file, "new")
        } finally {
            locker.destroyForcibly().waitFor()
        }

        assertTrue(result.isConflict, "Got $result")
        // Read once the lock is gone: Windows locks keep other handles from reading the range too
        assertEquals(old, Files.readString(file))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `editing a read-only file is refused`() {
        val file = Files.writeString(tempDir.resolve("a.txt"), "old")
        setReadOnly(file)

        val result = edit(file, "new")

        assertTrue(result.rejected)
        assertEquals("File is read-only.", result.error)
        assertEquals("old", Files.readString(file))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `editing a locked file is a conflict and keeps its content`() {
        val file = Files.writeString(tempDir.resolve("a.txt"), "old")

        val result = withFileLocked(file) { edit(file, "new") }

        assertTrue(result.isConflict, "Got $result")
        assertEquals("File is in use.", result.error)
        assertEquals("old", Files.readString(file))
    }
}
