package org.filemat.server.module.file.service.filesystem.fileOperation

import io.mockk.every
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import org.filemat.server.common.model.Result
import org.filemat.server.common.State
import org.filemat.server.config.platform.WindowsNative
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.testutil.FileOperationMocks
import org.filemat.server.testutil.assertNotSuccessful
import org.filemat.server.testutil.assertSuccess
import org.filemat.server.testutil.createCaseSensitiveFolder
import org.filemat.server.testutil.isReadOnly
import org.filemat.server.testutil.junctionOrSkip
import org.filemat.server.testutil.setReadOnly
import org.filemat.server.testutil.symlinkOrSkip
import org.filemat.server.testutil.withFileLocked
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test

class FilesystemCopyServiceTest {

    @TempDir
    lateinit var tempDir: Path

    // The data folder is not used: the copy tests never touch it
    private val mocks = FileOperationMocks { tempDir }
    private val fileService = mocks.fileService
    private val user = mocks.user

    private val readable = mutableSetOf<Path>()

    private val service = FilesystemCopyService(
        logService = mocks.logService,
        fileService = fileService,
        fileLockService = mocks.fileLockService,
        state = State(env = emptyMap()),
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

        assertNotSuccessful(result)
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

        assertSuccess(result)
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

        assertNotSuccessful(result)
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

        assertNotSuccessful(result)
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

        assertNotSuccessful(result)
        assertTrue(Files.exists(dest.resolve("ok.txt")))
        assertFalse(Files.exists(dest.resolve("a")))
    }

    @Test
    fun `junction in a folder does not copy its target contents`() {
        val outside = tempDir.resolve("outside")
        Files.createDirectories(outside)
        val outsideFile = Files.writeString(outside.resolve("secret.txt"), "secret")
        val source = tempDir.resolve("folder")
        val dest = tempDir.resolve("dest")
        Files.createDirectories(source)
        Files.writeString(source.resolve("ok.txt"), "ok")
        junctionOrSkip(source.resolve("junction"), outside)
        readable.add(source.resolve("ok.txt"))
        readable.add(source.resolve("junction"))

        val result = copy(source, dest)

        // The rest is copied, and nothing is made where the junction was
        assertTrue(result.isNotSuccessful)
        assertEquals("1 items could not be copied. Links to folders were not copied.", result.error)
        assertTrue(Files.exists(dest.resolve("ok.txt")))
        assertFalse(Files.exists(dest.resolve("junction"), LinkOption.NOFOLLOW_LINKS))
        assertEquals("secret", Files.readString(outsideFile))
    }

    @Test
    fun `a junction alone is not copied`() {
        val outside = Files.createDirectories(tempDir.resolve("outside"))
        Files.writeString(outside.resolve("secret.txt"), "secret")
        val junction = tempDir.resolve("junction")
        junctionOrSkip(junction, outside)
        readable.add(junction)
        val dest = tempDir.resolve("copy")

        val result = copy(junction, dest)

        assertEquals("Links to folders cannot be copied.", result.error)
        assertFalse(Files.exists(dest, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `a folder with only a junction makes no folder at the destination`() {
        val outside = Files.createDirectories(tempDir.resolve("outside"))
        val source = Files.createDirectories(tempDir.resolve("folder"))
        junctionOrSkip(source.resolve("junction"), outside)
        readable.add(source.resolve("junction"))
        val dest = tempDir.resolve("dest")

        val result = copy(source, dest)

        assertEquals("1 items could not be copied. Links to folders were not copied.", result.error)
        assertFalse(Files.exists(dest, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `a symbolic link to a folder is copied as a link`() {
        val outside = Files.createDirectories(tempDir.resolve("outside"))
        Files.writeString(outside.resolve("secret.txt"), "secret")
        val source = Files.createDirectories(tempDir.resolve("folder"))
        symlinkOrSkip(source.resolve("link"), outside)
        readable.add(source.resolve("link"))
        val dest = tempDir.resolve("dest")

        assertSuccess(copy(source, dest))

        assertTrue(Files.isSymbolicLink(dest.resolve("link")))
        assertEquals(outside, Files.readSymbolicLink(dest.resolve("link")))
    }

    private fun copy(source: Path, dest: Path) = service.copyFile(
        canonicalSource = FilePath.ofAlreadyNormalized(source),
        canonicalDestination = FilePath.ofAlreadyNormalized(dest),
        user = user,
        ignorePermissions = false,
        copyResolvedSymlinks = false,
    )

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a copy of a read-only file is read-only`() {
        val source = Files.writeString(tempDir.resolve("a.txt"), "x")
        setReadOnly(source)
        readable.add(source)
        val dest = tempDir.resolve("b.txt")

        assertSuccess(copy(source, dest))

        assertTrue(isReadOnly(dest))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a copy to a name that differs only in case already exists`() {
        val source = Files.writeString(tempDir.resolve("a.txt"), "x")
        readable.add(source)

        val result = copy(source, tempDir.resolve("A.txt"))

        assertNotSuccessful(result)
        assertEquals("A file or folder already exists at the destination.", result.error)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a file locked by another program is not copied`() {
        val source = Files.writeString(tempDir.resolve("a.txt"), "x")
        readable.add(source)
        val dest = tempDir.resolve("b.txt")

        val result = withFileLocked(source) { copy(source, dest) }

        assertTrue(result.isConflict, "Got $result")
        assertEquals("File is in use.", result.error)
        assertFalse(Files.exists(dest))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a case-sensitive folder stays case-sensitive, with names that differ only in case`() {
        val source = Files.createDirectory(tempDir.resolve("src"))
        val caseSensitive = createCaseSensitiveFolder(source, "cs")
        val lower = Files.writeString(caseSensitive.resolve("a.txt"), "lower")
        val upper = Files.writeString(caseSensitive.resolve("A.txt"), "upper")
        val empty = createCaseSensitiveFolder(source, "empty")
        val dest = Files.createDirectory(tempDir.resolve("dest")).resolve("src")
        readable.addAll(listOf(lower, upper))

        assertSuccess(copy(source, dest))

        val copiedFolder = dest.resolve("cs")
        assertEquals(true, WindowsNative.isCaseSensitiveDirectory(copiedFolder))
        assertEquals(true, WindowsNative.isCaseSensitiveDirectory(dest.resolve("empty")))
        assertEquals(false, WindowsNative.isCaseSensitiveDirectory(dest))
        assertEquals("lower", Files.readString(copiedFolder.resolve("a.txt")))
        assertEquals("upper", Files.readString(copiedFolder.resolve("A.txt")))
        assertTrue(Files.isDirectory(empty))
    }
}
