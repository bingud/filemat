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
import org.filemat.server.testutil.assertNotFound
import org.filemat.server.testutil.assertRejected
import org.filemat.server.testutil.assertSuccess
import org.filemat.server.testutil.junctionOrSkip
import org.filemat.server.testutil.symlinkOrSkip
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FileDeletionServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val fileService = mockk<FileService>()
    private val filesystemService = mockk<FilesystemService>(relaxed = true)
    private val user = mockk<Principal>(relaxed = true)
    private val state = State(env = emptyMap())

    private val service = FileDeletionService(
        fileService = fileService,
        filesystemService = filesystemService,
        savedFileService = mockk(relaxed = true),
        state = state,
    )

    @BeforeEach
    fun setup() {
        every { fileService.isAllowedToDeleteFile(any(), any(), any()) } returns Result.ok()
        every { filesystemService.exists(any(), any()) } returns true
        every { filesystemService.deleteFile(target = any(), user = any()) } returns Result.ok()
    }

    private fun delete(path: Path) = service.deleteFile(user, FilePath.ofAlreadyNormalized(path))

    /**
     * `exposed/linked` is a link to `hidden`, a folder outside, and `hidden/link` is the link that the request deletes
     */
    private fun linkInLinkedFolder(): Pair<Path, Path> {
        val real = tempDir.toRealPath()
        val hidden = Files.createDirectory(real.resolve("hidden"))
        val link = hidden.resolve("link")
        symlinkOrSkip(link, Files.writeString(real.resolve("target.txt"), "x"))
        val exposed = Files.createDirectory(real.resolve("exposed"))
        symlinkOrSkip(exposed.resolve("linked"), hidden)
        return exposed.resolve("linked").resolve("link") to link
    }

    @Test
    fun `with links off, a link inside a linked folder is not deleted`() {
        state.app.followSymlinks = false
        val (requested, _) = linkInLinkedFolder()

        assertNotFound(delete(requested))

        verify(exactly = 0) { filesystemService.deleteFile(target = any(), user = any()) }
    }

    @Test
    fun `with links on, a link inside a linked folder is checked where it really is`() {
        state.app.followSymlinks = true
        val (requested, real) = linkInLinkedFolder()

        assertSuccess(delete(requested))

        verify { fileService.isAllowedToDeleteFile(user = user, canonicalPath = FilePath.ofAlreadyNormalized(real)) }
        verify { filesystemService.deleteFile(target = FilePath.ofAlreadyNormalized(real), user = user) }
    }

    /**
     * `folder/junction` is a junction to `target`, which holds a file
     */
    private fun junctionToFolder(): Pair<Path, Path> {
        val real = tempDir.toRealPath()
        val target = Files.createDirectory(real.resolve("target"))
        Files.writeString(target.resolve("keep.txt"), "x")
        val folder = Files.createDirectory(real.resolve("folder"))
        val junction = folder.resolve("junction")
        junctionOrSkip(junction, target)
        return junction to target
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `with links on, a junction is deleted itself, not the folder it points to`() {
        state.app.followSymlinks = true
        val (junction, target) = junctionToFolder()

        assertSuccess(delete(junction))

        verify { fileService.isAllowedToDeleteFile(user = user, canonicalPath = FilePath.ofAlreadyNormalized(junction)) }
        verify { filesystemService.deleteFile(target = FilePath.ofAlreadyNormalized(junction), user = user) }
        verify(exactly = 0) { filesystemService.deleteFile(target = FilePath.ofAlreadyNormalized(target), user = any()) }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `with links off, a junction can be deleted`() {
        state.app.followSymlinks = false
        val (junction, _) = junctionToFolder()

        assertSuccess(delete(junction))

        verify { filesystemService.deleteFile(target = FilePath.ofAlreadyNormalized(junction), user = user) }
    }

    @Test
    fun `a root folder is not deleted`() {
        assertRejected(delete(tempDir.toRealPath().root))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a path without a drive is not found`() {
        // `\name` would resolve on the drive the server was started from
        assertNotFound(delete(Path.of("\\" + tempDir.toRealPath().fileName)))

        verify(exactly = 0) { fileService.isAllowedToDeleteFile(any(), any(), any()) }
    }
}
