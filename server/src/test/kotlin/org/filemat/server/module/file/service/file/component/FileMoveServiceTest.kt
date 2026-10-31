package org.filemat.server.module.file.service.file.component

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.testutil.assertRejected
import org.filemat.server.testutil.assertSuccess
import org.filemat.server.testutil.assumeWindowsFeature
import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FileMoveServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val fileService = mockk<FileService>()
    private val filesystemService = mockk<FilesystemService>()
    private val user = mockk<Principal>(relaxed = true)

    private val service = FileMoveService(
        fileService = fileService,
        savedFileService = mockk(relaxed = true),
        filesystemService = filesystemService,
        state = State(env = emptyMap()),
    )

    @BeforeEach
    fun setup() {
        every { fileService.isAllowedToEditFile(any(), any(), any()) } returns Result.ok()
        every { filesystemService.moveFile(any(), any(), any(), any(), any(), any()) } returns Result.ok()
    }

    private fun move(source: Path, dest: Path) = service.moveFile(user, FilePath.ofAlreadyNormalized(source), FilePath.ofAlreadyNormalized(dest))

    @Test
    fun `a folder cannot be moved into itself`() {
        val folder = Files.createDirectory(tempDir.toRealPath().resolve("folder"))
        val inside = Files.createDirectory(folder.resolve("inside"))

        val result = move(folder, inside.resolve("folder"))

        assertRejected(result)
        assertEquals("File cannot be moved into itself.", result.error)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a change of case is a rename, not a move into itself`() {
        val real = tempDir.toRealPath()
        Files.writeString(real.resolve("photo.jpg"), "x")

        assertSuccess(move(real.resolve("photo.jpg"), real.resolve("Photo.jpg")))

        verify { filesystemService.moveFile(source = any(), destination = any(), user = user, isRename = true) }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a name given as its 8_3 short name is moved under its real name`() {
        // Block lists such as the sensitive list compare names, so `CREDEN~1` must not stand in for `Credentials`
        val real = tempDir.toRealPath()
        val folder = Files.createDirectory(real.resolve("Credentials"))
        val buffer = CharArray(32_768)
        val length = Kernel32.INSTANCE.GetShortPathName(folder.toString(), buffer, buffer.size)
        val short = if (length == 0) null else Path.of(Native.toString(buffer)).fileName.toString()
        assumeWindowsFeature(short != null && !short.equals("Credentials", ignoreCase = true), "8.3 names are off on this volume")

        assertSuccess(move(real.resolve(short!!), real.resolve("moved")))

        verify { filesystemService.moveFile(source = FilePath.ofAlreadyNormalized(folder), destination = any(), user = user, isRename = true) }
    }
}
