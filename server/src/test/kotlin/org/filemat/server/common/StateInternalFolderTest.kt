package org.filemat.server.common

import org.filemat.server.testutil.junctionOrSkip
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests which files count as Filemat's own working files: only files inside the real upload or cache folder.
 */
class StateInternalFolderTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var dataFolder: Path
    private lateinit var state: State

    @BeforeEach
    fun setup() {
        dataFolder = Files.createDirectory(tempDir.resolve("data")).toRealPath()
        state = State(env = emptyMap(), dataFolderPath = dataFolder)
    }

    private fun uploadFolder(path: Path) = path.also { state.app.uploadFolderPath = it.toString() }

    @Test
    fun `a file in the upload folder inside the data folder is internal`() {
        val uploads = uploadFolder(Files.createDirectory(dataFolder.resolve("uploads")))
        val file = Files.createDirectories(uploads.resolve("uploads").resolve("id")).resolve("data").also { Files.writeString(it, "x") }

        assertTrue(state.isInInternalFolder(InternalFolder.UPLOADS, file))
        assertEquals(uploads, state.internalFolderPath(InternalFolder.UPLOADS))
    }

    @Test
    fun `the folder itself and files outside it are not internal`() {
        val uploads = uploadFolder(Files.createDirectory(dataFolder.resolve("uploads")))
        val database = Files.writeString(dataFolder.resolve("filemat-server.db"), "x")

        assertFalse(state.isInInternalFolder(InternalFolder.UPLOADS, uploads))
        assertFalse(state.isInInternalFolder(InternalFolder.UPLOADS, database))
        assertFalse(state.isInInternalFolder(InternalFolder.THUMBNAIL_CACHE, database))
    }

    @Test
    fun `the data folder and its parents are never internal folders`() {
        val database = Files.writeString(dataFolder.resolve("filemat-server.db"), "x")

        uploadFolder(dataFolder)
        assertNull(state.internalFolderPath(InternalFolder.UPLOADS))
        assertFalse(state.isInInternalFolder(InternalFolder.UPLOADS, database))

        uploadFolder(tempDir)
        assertNull(state.internalFolderPath(InternalFolder.UPLOADS))
        assertFalse(state.isInInternalFolder(InternalFolder.UPLOADS, database))
    }

    @Test
    fun `a missing file is not internal`() {
        val uploads = uploadFolder(Files.createDirectory(dataFolder.resolve("uploads")))

        assertFalse(state.isInInternalFolder(InternalFolder.UPLOADS, uploads.resolve("missing")))
    }

    @Test
    fun `a link in the upload folder to a folder outside it is not internal`() {
        val uploads = uploadFolder(Files.createDirectory(dataFolder.resolve("uploads")))
        val outside = Files.createDirectory(tempDir.resolve("outside"))
        Files.writeString(outside.resolve("file.txt"), "x")
        junctionOrSkip(uploads.resolve("link"), outside)

        assertFalse(state.isInInternalFolder(InternalFolder.UPLOADS, uploads.resolve("link").resolve("file.txt")))
    }

    @Test
    fun `an upload folder set through a link is compared by its real path`() {
        // FM_DATA_DIR on a junction: the defaults name the link, the checks use the real folder
        val uploads = Files.createDirectory(dataFolder.resolve("uploads"))
        val file = Files.writeString(uploads.resolve("data"), "x")
        val dataLink = tempDir.resolve("datalink")
        junctionOrSkip(dataLink, dataFolder)
        try {
            uploadFolder(dataLink.resolve("uploads"))

            assertEquals(uploads, state.internalFolderPath(InternalFolder.UPLOADS))
            assertTrue(state.isInInternalFolder(InternalFolder.UPLOADS, dataLink.resolve("uploads").resolve("data")))
            assertTrue(state.isInInternalFolder(InternalFolder.UPLOADS, file))
        } finally {
            // Removes the link only. The temp folder cleanup can't delete a link whose target is already gone.
            Files.delete(dataLink)
        }
    }
}
