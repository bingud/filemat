package org.filemat.server.module.file.service.file

import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import org.filemat.server.common.State
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.file.media.FfmpegBusyException
import org.filemat.server.module.file.service.file.media.MediaPreviewBackend
import org.filemat.server.module.file.service.file.media.MediaPreviewService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.io.path.listDirectoryEntries

/**
 * A busy FFmpeg is not the file's fault: no error text is written as the preview, and nothing is cached (R12).
 */
class ThumbnailServiceBusyTest {

    @TempDir
    lateinit var tempDir: Path

    private val backend = mockk<MediaPreviewBackend> {
        every { firstFrame(any()) } throws FfmpegBusyException()
    }

    private fun service(cacheFolder: Path): ThumbnailService {
        val state = State(env = emptyMap(), dataFolderPath = Files.createDirectory(tempDir.resolve("data")).toRealPath())
            .apply { thumbCache.isEnabled = false }
        val service = ThumbnailService(
            logService = mockk(relaxed = true),
            fileContentMetadataService = mockk(relaxed = true),
            state = state,
            mediaPreviewService = mockk<MediaPreviewService> { every { backend } returns this@ThumbnailServiceBusyTest.backend },
        )
        state.thumbCache.isEnabled = true
        state.thumbCache.folderPath = cacheFolder.toString()
        return service
    }

    @Test
    fun `a video preview throws when FFmpeg is busy, and caches nothing`() {
        val cache = Files.createDirectory(tempDir.resolve("cache"))
        val video = Files.writeString(tempDir.resolve("clip.mp4"), "x")
        val output = ByteArrayOutputStream()

        assertThrows(FfmpegBusyException::class.java) {
            service(cache).streamVideoPreview(FilePath.of(video.toString()), 48, Files.getLastModifiedTime(video).toMillis(), 1, output)
        }

        assertEquals(0, output.size())
        assertEquals(emptyList<Path>(), cache.listDirectoryEntries())
    }

    @Test
    fun `an image that needs FFmpeg throws when it is busy, and caches nothing`() {
        val cache = Files.createDirectory(tempDir.resolve("cache"))
        // Not an image Thumbnailator can read, so the FFmpeg fallback runs
        val image = Files.writeString(tempDir.resolve("photo.heic"), "x")
        val output = ByteArrayOutputStream()

        assertThrows(FfmpegBusyException::class.java) {
            service(cache).streamImageThumbnail(
                FilePath.of(image.toString()), 48, Files.getLastModifiedTime(image).toMillis(), 1,
                inputStreamProvider = { Files.newInputStream(image) },
                outputStream = output,
            )
        }

        assertEquals(0, output.size())
        assertEquals(emptyList<Path>(), cache.listDirectoryEntries())
    }
}
