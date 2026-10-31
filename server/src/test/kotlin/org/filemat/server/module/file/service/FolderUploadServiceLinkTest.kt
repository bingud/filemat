package org.filemat.server.module.file.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.common.InternalFolder
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.config.platform.PathPolicy
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FolderUploadFileManifest
import org.filemat.server.module.file.model.FolderUploadManifest
import org.filemat.server.module.file.model.FolderUploadSessionRequest
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.testutil.assertRejected
import org.filemat.server.testutil.assertSuccess
import org.filemat.server.testutil.directoryLinkOrSkip
import org.filemat.server.testutil.testPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Folder uploads and links: every check runs on the folder the files really land in.
 * A link is refused when links are not followed, and its target is checked when they are.
 *
 * The edit check stands in for the real one, which compares paths as strings: only paths inside `expose` are allowed.
 */
class FolderUploadServiceLinkTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var expose: Path
    private lateinit var other: Path
    private lateinit var state: State
    private lateinit var service: FolderUploadService

    private val fileService = mockk<FileService>()
    private val filesystemService = mockk<FilesystemService>()
    private val user = testPrincipal()

    @BeforeEach
    fun setup() {
        expose = Files.createDirectory(tempDir.resolve("expose")).toRealPath()
        other = Files.createDirectory(tempDir.resolve("other")).toRealPath()
        state = State(env = emptyMap(), dataFolderPath = Files.createDirectory(tempDir.resolve("data")).toRealPath())
        service = FolderUploadService(
            fileService = fileService,
            filesystemService = filesystemService,
            entityService = mockk(),
            fileLockService = FileLockService(),
            thumbnailService = mockk(relaxed = true),
            state = state,
        )

        every { fileService.isAllowedToEditFile(any(), any(), any()) } answers {
            val path = secondArg<FilePath>()
            if (PathPolicy.isKeyWithin(path.pathKey, FilePath.ofAlreadyNormalized(expose).pathKey)) Result.ok() else Result.reject("This file is not exposed.")
        }
        every { fileService.ensureEntityIndexed(any(), any(), any(), any()) } returns Result.ok()
        every { filesystemService.createFolder(any()) } answers {
            Files.createDirectories(firstArg<FilePath>().path)
            Result.ok()
        }
        every { filesystemService.moveFile(any(), any(), any(), any(), any(), any()) } returns Result.ok()
    }

    private fun manifest(rootName: String, vararg files: String) = FolderUploadManifest(
        destinationPath = expose.toString(),
        rootName = rootName,
        files = files.map { FolderUploadFileManifest(relativePath = it, size = 1, lastModified = 0) },
    )

    private fun session(manifest: FolderUploadManifest) = service.createSession(user, FolderUploadSessionRequest(manifest))

    @Test
    fun `a folder upload into a link to an unexposed folder is refused`() {
        directoryLinkOrSkip(expose.resolve("j"), other)
        val manifest = manifest("j", "j/evil.txt")

        val preflight = service.preflight(user, manifest)

        assertSuccess(preflight)
        assertEquals(listOf("j", "j/evil.txt"), preflight.value.blocked.map { it.relativePath }.sorted())
        assertRejected(session(manifest))
    }

    @Test
    fun `a link deeper in the uploaded tree is refused`() {
        // Like `Local Settings` in a profile, which leads to AppData\Local and the default data folder
        Files.createDirectory(expose.resolve("root"))
        directoryLinkOrSkip(expose.resolve("root").resolve("Local Settings"), other)

        val preflight = service.preflight(user, manifest("root", "root/ok.txt", "root/Local Settings/Filemat/x.txt"))

        assertEquals(
            listOf("root/Local Settings/Filemat", "root/Local Settings/Filemat/x.txt"),
            preflight.value.blocked.map { it.relativePath }.sorted(),
        )
    }

    @Test
    fun `with links followed, the link's target is what gets checked`() {
        state.app.followSymlinks = true
        directoryLinkOrSkip(expose.resolve("j"), other)
        val manifest = manifest("j", "j/evil.txt")

        val blocked = service.preflight(user, manifest).value.blocked

        assertTrue(blocked.isNotEmpty())
        assertTrue(blocked.all { it.message == "This file is not exposed." })
        assertRejected(session(manifest))
    }

    @Test
    fun `a link that replaces a planned folder after the session is refused when the file arrives`() {
        val created = session(manifest("root", "root/sub/evil.txt"))
        assertSuccess(created)
        val sub = expose.resolve("root").resolve("sub")
        Files.delete(sub)
        directoryLinkOrSkip(sub, other)

        val result = service.finalizeTusUpload(
            user = user,
            sessionId = created.value.sessionId ?: "",
            relativePath = "root/sub/evil.txt",
            uploadLocation = FilePath.ofAlreadyNormalized(tempDir.resolve("upload")),
        )

        assertRejected(result)
        verify(exactly = 0) { filesystemService.moveFile(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a finished file is moved as one of Filemat's own uploads`() {
        val created = session(manifest("root", "root/a.txt"))
        val upload = FilePath.ofAlreadyNormalized(tempDir.resolve("upload"))

        assertSuccess(service.finalizeTusUpload(user, created.value.sessionId ?: "", "root/a.txt", upload))

        verify {
            filesystemService.moveFile(
                source = upload,
                destination = FilePath.ofAlreadyNormalized(expose.resolve("root").resolve("a.txt")),
                user = user,
                ignorePermissions = null,
                isRename = null,
                internalFolder = InternalFolder.UPLOADS,
            )
        }
        assertFalse(Files.exists(other.resolve("a.txt")))
    }
}
