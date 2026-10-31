package org.filemat.server.module.file.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.filemat.server.common.State
import org.filemat.server.config.platform.CaseSensitivity
import org.filemat.server.config.platform.PathPolicy
import org.filemat.server.module.file.model.FolderUploadDirectoryManifest
import org.filemat.server.module.file.model.FolderUploadFileManifest
import org.filemat.server.module.file.model.FolderUploadManifest
import org.filemat.server.testutil.assertRejected
import org.filemat.server.testutil.createCaseSensitiveFolder
import org.filemat.server.testutil.testPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests the name checks of folder upload manifests. They run before any file or entity is touched,
 * so the collaborators are plain mocks that fail if called.
 */
class FolderUploadServiceNameTest {

    private val service = FolderUploadService(
        fileService = mockk(),
        filesystemService = mockk(),
        entityService = mockk(),
        fileLockService = mockk(),
        thumbnailService = mockk(),
        state = State(env = emptyMap()),
    )

    private fun preflightError(
        rootName: String,
        directories: List<String> = emptyList(),
        files: List<String> = emptyList(),
    ): String? {
        val manifest = FolderUploadManifest(
            destinationPath = "/uploads",
            rootName = rootName,
            directories = directories.map { FolderUploadDirectoryManifest(it) },
            files = files.map { FolderUploadFileManifest(relativePath = it, size = 1, lastModified = 0) },
        )
        val result = service.preflight(testPrincipal(), manifest)
        assertRejected(result)
        return result.error
    }

    @Test
    fun `a root name over the filename limit is rejected`() {
        assertEquals("Name is too long.", preflightError(rootName = "a".repeat(256)))
    }

    @Test
    fun `a long directory name is rejected`() {
        assertEquals("Name is too long.", preflightError(rootName = "root", directories = listOf("root/" + "d".repeat(256))))
    }

    @Test
    fun `a long file name is rejected`() {
        assertEquals("Name is too long.", preflightError(rootName = "root", files = listOf("root/" + "f".repeat(256))))
    }

    @Test
    fun `dot segments are rejected`() {
        assertEquals("Path contains invalid segments.", preflightError(rootName = "root", files = listOf("root/../x")))
        assertEquals("Path contains invalid segments.", preflightError(rootName = "root", files = listOf("root/./x")))
    }

    @Test
    fun `absolute paths are rejected`() {
        assertEquals("Absolute paths are not allowed.", preflightError(rootName = "/root"))
    }

    @Test
    fun `control characters are rejected`() {
        assertEquals("Path contains invalid characters.", preflightError(rootName = "ro\u0001ot"))
    }

    @Test
    fun `paths outside the uploaded folder are rejected`() {
        assertEquals("File path is outside the uploaded folder.", preflightError(rootName = "root", files = listOf("other/x")))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a trailing dot or space is rejected on Windows`() {
        assertEquals("Name cannot end with a dot or space.", preflightError(rootName = "root."))
        assertEquals("Name cannot end with a dot or space.", preflightError(rootName = "root", files = listOf("root/x ")))
    }

    /**
     * The duplicate message of a manifest that only has [files], or null when the manifest got past that check.
     * Past it, the service needs real collaborators, so a failure there counts as "no duplicate".
     */
    private fun duplicateError(files: List<String>, destination: String = "C:/filemat-no-such-folder"): String? {
        val manifest = FolderUploadManifest(
            destinationPath = destination,
            rootName = "root",
            directories = emptyList(),
            files = files.map { FolderUploadFileManifest(relativePath = "root/$it", size = 1, lastModified = 0) },
        )
        val error = runCatching { service.preflight(testPrincipal(), manifest).error }.getOrNull()
        return error?.takeIf { it.startsWith("Duplicate file path") }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `names that differ only in case are duplicates on Windows`() {
        val error = preflightError(rootName = "root", files = listOf("root/A.txt", "root/a.txt"))

        assertEquals("Duplicate file path in upload manifest: root/A.txt", error)
    }

    // The fold is the NTFS one (simple upper case per code point), not `lowercase()`
    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `sharp s and capital sharp s are different names on Windows`() {
        assertNull(duplicateError(listOf("stra\u00DFe.txt", "stra\u1E9Ee.txt")))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `the Kelvin sign is not a k on Windows`() {
        assertNull(duplicateError(listOf("k.txt", "\u212A.txt")))
        assertNull(duplicateError(listOf("K.txt", "\u212A.txt")))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `dotless i and i are duplicates on Windows`() {
        assertEquals("Duplicate file path in upload manifest: root/i.txt", duplicateError(listOf("i.txt", "\u0131.txt")))
        assertEquals("Duplicate file path in upload manifest: root/I.txt", duplicateError(listOf("I.txt", "\u0131.txt")))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `names that differ only in case are not duplicates in a case-sensitive folder`(@TempDir dir: Path) {
        val folder = createCaseSensitiveFolder(dir, "sensitive")
        val normal = Files.createDirectory(dir.resolve("normal"))
        val files = listOf("a.txt", "A.txt")

        assertNull(duplicateError(files, destination = PathPolicy.toApiString(folder)))
        assertEquals("Duplicate file path in upload manifest: root/a.txt", duplicateError(files, destination = PathPolicy.toApiString(normal)))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `the destination flag decides the case rule`() {
        mockkObject(CaseSensitivity)
        try {
            every { CaseSensitivity.isCaseSensitive(any()) } returns true
            assertNull(duplicateError(listOf("a.txt", "A.txt")))
            // Still the same name, also in a case-sensitive folder
            assertEquals("Duplicate file path in upload manifest: root/a.txt", duplicateError(listOf("a.txt", "a.txt")))

            every { CaseSensitivity.isCaseSensitive(any()) } returns false
            assertEquals("Duplicate file path in upload manifest: root/a.txt", duplicateError(listOf("a.txt", "A.txt")))
        } finally {
            unmockkObject(CaseSensitivity)
        }
    }
}
