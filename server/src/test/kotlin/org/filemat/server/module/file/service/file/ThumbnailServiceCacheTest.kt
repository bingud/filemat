package org.filemat.server.module.file.service.file

import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import org.filemat.server.common.State
import org.filemat.server.module.file.model.FilePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ThumbnailServiceCacheTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var dataFolder: Path
    private lateinit var state: State
    private lateinit var service: ThumbnailService

    @BeforeEach
    fun setup() {
        dataFolder = Files.createDirectory(tempDir.resolve("data")).toRealPath()
        // No cache folder yet, so the cleanup at startup does nothing
        state = State(env = emptyMap(), dataFolderPath = dataFolder).apply { thumbCache.isEnabled = false }
        service = ThumbnailService(
            logService = mockk(relaxed = true),
            fileContentMetadataService = mockk(relaxed = true),
            state = state,
            mediaPreviewService = mockk(relaxed = true),
        )
    }

    private fun cacheFileWith(cacheFolder: Path, file: Path = tempDir.resolve("photos").resolve("photo.png")) = run {
        state.thumbCache.isEnabled = true
        state.thumbCache.folderPath = cacheFolder.toString()
        service.getCacheFile(FilePath.of(file.toString()), modifiedDate = 1, fileSize = 2, targetSize = 48)
    }

    @Test
    fun `a cache folder in the data folder is used`() {
        // Not created: getCacheFile only builds the path
        assertNotNull(cacheFileWith(dataFolder.resolve("cache")))
    }

    @Test
    fun `a cache folder that is not exposed is used`() {
        assertNotNull(cacheFileWith(tempDir.resolve("cache")))
    }

    @Test
    fun `the data folder and its parents are never the cache folder`() {
        assertNull(cacheFileWith(dataFolder))
        assertNull(cacheFileWith(tempDir))
    }

    @Test
    fun `a file inside the cache folder is not cached`() {
        val cache = tempDir.resolve("cache")
        assertNull(cacheFileWith(cache, file = cache.resolve("photo.png")))
    }

    @Test
    fun `clearing the cache deletes only cache files`() {
        val cache = Files.createDirectory(tempDir.resolve("cache"))
        val cacheFile = Files.writeString(cache.resolve(cacheName()), "x")
        val other = Files.writeString(cache.resolve("notes.txt"), "keep")

        service.clearCache(cache)

        assertFalse(Files.exists(cacheFile))
        assertEquals("keep", Files.readString(other))
    }

    @Test
    fun `moving the cache moves only cache files`() {
        val from = Files.createDirectory(tempDir.resolve("old"))
        val to = Files.createDirectory(tempDir.resolve("new"))
        Files.writeString(from.resolve(cacheName()), "x")
        val other = Files.writeString(from.resolve("notes.txt"), "keep")

        service.moveCache(from, to)

        assertTrue(Files.exists(to.resolve(cacheName())))
        assertFalse(Files.exists(from.resolve(cacheName())))
        assertEquals("keep", Files.readString(other))
    }

    private fun cacheName() = "${"a".repeat(32)}_1_2_48.jpg"
}
