package org.filemat.server.module.file.service.file.component

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import javax.imageio.ImageIO
import org.filemat.server.common.model.Result
import org.filemat.server.config.platform.LinkInspector
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FileContentMetadata
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.file.media.FfmpegBusyException
import org.filemat.server.module.file.service.file.media.MediaPreviewBackend
import org.filemat.server.module.file.service.file.media.MediaPreviewService
import org.filemat.server.testutil.assertSuccess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test

class FileContentMetadataServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val fileService = mockk<FileService>()
    private val backend = mockk<MediaPreviewBackend>()
    private val mediaPreviewService = mockk<MediaPreviewService> {
        every { backend } returns this@FileContentMetadataServiceTest.backend
    }
    private val service = FileContentMetadataService(
        fileService = fileService,
        fileLockService = FileLockService(),
        mediaPreviewService = mediaPreviewService,
    )

    @Test
    fun `reads png dimensions from the image header`() {
        val file = tempDir.resolve("photo.png").toFile()
        ImageIO.write(BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB), "png", file)

        val meta = service.extractFromFile(file, "photo.png")

        assertEquals(32, meta.width)
        assertEquals(16, meta.height)
        assertNull(meta.durationMs)
    }

    @Test
    fun `an image header far into a file is reached by seeking, not by reading the file into memory`() {
        // libtiff writes the header (IFD) after the image data, so a large TIFF points to its end
        val file = tempDir.resolve("big.tif")
        val ifdOffset = 3L * 1024 * 1024 * 1024
        Files.newByteChannel(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE).use { channel ->
            val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            header.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(ifdOffset.toInt())
            channel.write(header.flip())

            // Width, height, 8-bit grayscale, uncompressed, one strip of image data right after the header
            val tags = listOf(256 to 640, 257 to 480, 258 to 8, 259 to 1, 262 to 1, 273 to 8, 277 to 1, 278 to 480, 279 to 640 * 480)
            val ifd = ByteBuffer.allocate(2 + tags.size * 12 + 4).order(ByteOrder.LITTLE_ENDIAN)
            ifd.putShort(tags.size.toShort())
            for ((tag, value) in tags) ifd.putShort(tag.toShort()).putShort(4).putInt(1).putInt(value)
            ifd.putInt(0)
            channel.position(ifdOffset)
            channel.write(ifd.flip())
        }

        val meta = assertTimeoutPreemptively<FileContentMetadata>(Duration.ofSeconds(30)) { service.extractFromFile(file.toFile(), "big.tif") }

        assertEquals(640, meta.width)
        assertEquals(480, meta.height)
    }

    @Test
    fun `video metadata comes from the FFmpeg backend`() {
        val file = tempDir.resolve("clip.mp4").toFile()
        file.writeText("x")
        every { backend.probe(file.toPath()) } returns FileContentMetadata(width = 64, height = 48, durationMs = 1000)

        val meta = service.extractFromFile(file, "clip.mp4")

        assertEquals(FileContentMetadata(width = 64, height = 48, durationMs = 1000), meta)
    }

    @Test
    fun `without FFmpeg, video metadata is empty`() {
        val file = tempDir.resolve("clip.mp4").toFile()
        file.writeText("x")
        every { mediaPreviewService.backend } returns null

        assertEquals(FileContentMetadata(), service.extractFromFile(file, "clip.mp4"))
    }

    @Test
    fun `audio metadata has no frame size`() {
        val file = tempDir.resolve("song.mp3").toFile()
        file.writeText("x")
        every { backend.probe(file.toPath()) } returns FileContentMetadata(width = 500, height = 500, durationMs = 3000)

        assertEquals(FileContentMetadata(durationMs = 3000), service.extractFromFile(file, "song.mp3"))
    }

    @Test
    fun `getContentMetadata returns png dimensions after access check`() {
        val file = tempDir.resolve("shot.png").toFile()
        ImageIO.write(BufferedImage(48, 24, BufferedImage.TYPE_INT_RGB), "png", file)
        val path = FilePath.of(file.absolutePath)
        val user = mockk<Principal>(relaxed = true)
        mockAccess(path)

        val result = service.getContentMetadata(user, path, null)

        assertSuccess(result)
        assertEquals(48, result.value.width)
        assertEquals(24, result.value.height)
        assertNull(result.value.durationMs)
    }

    @Test
    fun `cache hit returns probed values without reading the png header`() {
        val file = tempDir.resolve("cached.png").toFile()
        ImageIO.write(BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB), "png", file)
        val path = FilePath.of(file.absolutePath)
        val user = mockk<Principal>(relaxed = true)
        mockAccess(path)

        service.putFromProbe(
            canonicalPath = path,
            modifiedDate = file.lastModified(),
            fileSize = file.length(),
            meta = FileContentMetadata(width = 99, height = 88),
        )

        val result = service.getContentMetadata(user, path, null)

        assertSuccess(result)
        assertEquals(99, result.value.width)
        assertEquals(88, result.value.height)
    }

    @Test
    fun `different mtime key is a cache miss and extracts the png`() {
        val file = tempDir.resolve("stale.png").toFile()
        ImageIO.write(BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB), "png", file)
        val path = FilePath.of(file.absolutePath)
        val user = mockk<Principal>(relaxed = true)
        mockAccess(path)

        service.putFromProbe(
            canonicalPath = path,
            modifiedDate = 1L,
            fileSize = file.length(),
            meta = FileContentMetadata(width = 99, height = 88),
        )

        val result = service.getContentMetadata(user, path, null)

        assertSuccess(result)
        assertEquals(32, result.value.width)
        assertEquals(16, result.value.height)
    }

    @Test
    fun `batch omits files that fail access`() {
        val file = tempDir.resolve("ok.png").toFile()
        ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", file)
        val okPath = FilePath.of(file.absolutePath)
        val denied = FilePath.of(tempDir.resolve("denied.png").toAbsolutePath().toString())
        val user = mockk<Principal>(relaxed = true)

        every { fileService.resolvePathWithOptionalShare(any(), any()) } answers {
            Result.ok(invocation.args[0] as FilePath)
        }
        every { fileService.resolvePathWithOptionalShare(any(), any(), any()) } answers {
            Result.ok(invocation.args[0] as FilePath)
        }
        every { fileService.isAllowedToAccessFile(any(), okPath, any()) } returns Result.ok()
        every { fileService.isAllowedToAccessFile(any(), denied, any()) } returns Result.reject("denied")

        val result = service.getContentMetadataBatch(user, listOf(okPath, denied), null)

        assertEquals(1, result.size)
        assertEquals(8, result[okPath.pathString]?.width)
    }

    @Test
    fun `busy FFmpeg throws and caches nothing, so the next request reads the metadata`() {
        val file = tempDir.resolve("clip.mp4").toFile()
        file.writeText("x")
        val path = FilePath.of(file.absolutePath)
        every { backend.probe(file.toPath()) } throws FfmpegBusyException() andThen FileContentMetadata(width = 64, height = 48, durationMs = 1000)

        assertThrows(FfmpegBusyException::class.java) { service.getCachedOrExtract(file, "clip.mp4", path) }
        val meta = service.getCachedOrExtract(file, "clip.mp4", path)

        assertEquals(64, meta.width)
    }

    @Test
    fun `batch stops at busy FFmpeg and leaves the rest out, so the web asks again`() {
        val image = tempDir.resolve("a.png").toFile()
        ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", image)
        val video = tempDir.resolve("b.mp4").toFile().apply { writeText("x") }
        val later = tempDir.resolve("c.png").toFile()
        ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", later)
        val paths = listOf(image, video, later).map { FilePath.of(it.absolutePath) }
        every { fileService.resolvePathWithOptionalShare(any(), any()) } answers { Result.ok(invocation.args[0] as FilePath) }
        every { fileService.resolvePathWithOptionalShare(any(), any(), any()) } answers { Result.ok(invocation.args[0] as FilePath) }
        every { fileService.isAllowedToAccessFile(any(), any(), any()) } returns Result.ok()
        every { backend.probe(any()) } throws FfmpegBusyException()

        val result = service.getContentMetadataBatch(mockk<Principal>(relaxed = true), paths, null)

        assertEquals(setOf(paths[0].pathString), result.keys)
    }

    private fun mockAccess(path: FilePath) {
        every { fileService.resolvePathWithOptionalShare(any(), any()) } returns Result.ok(path)
        every { fileService.resolvePathWithOptionalShare(any(), any(), any()) } returns Result.ok(path)
        every { fileService.isAllowedToAccessFile(any(), any(), any()) } returns Result.ok()
    }

    @Test
    fun `a OneDrive online-only file is not read for its metadata`() {
        val file = tempDir.resolve("cloud.mp4").toFile()
        file.writeText("x")
        val path = FilePath.of(file.absolutePath)
        mockAccess(path)
        mockkObject(LinkInspector)
        try {
            every { LinkInspector.kindOf(any<Path>()) } returns LinkInspector.Kind.DATA

            val result = service.getContentMetadata(mockk<Principal>(relaxed = true), path, null)

            assertSuccess(result)
            assertEquals(FileContentMetadata(), result.value)
            verify(exactly = 0) { backend.probe(any()) }
        } finally {
            unmockkObject(LinkInspector)
        }
    }
}
