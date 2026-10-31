package org.filemat.server.module.file.service.file.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import javax.imageio.ImageIO

/**
 * Runs the ffmpeg that `FM_FFMPEG_PATH` names (the bundled one, for example), or else the one found on PATH, like the app does.
 * Skipped when there is none.
 */
class ExternalFfmpegBackendTest {

    @TempDir
    lateinit var tempDir: Path

    private val ffmpeg: Path? = System.getenv("FM_FFMPEG_PATH")
        ?.takeIf { it.isNotBlank() }
        ?.let { Path.of(it) }
        ?.takeIf { Files.isRegularFile(it) }
        ?: FfmpegLocator.fromSearchPath(System.getenv("PATH"))

    private val executable: Path get() = requireNotNull(ffmpeg)
    private val backend get() = ExternalFfmpegBackend(executable)

    @BeforeEach
    fun requireFfmpeg() {
        assumeTrue(ffmpeg != null, "ffmpeg is not on PATH and FM_FFMPEG_PATH is not set")
    }

    /**
     * A one second 64 × 48 video, made by ffmpeg itself
     */
    private fun makeVideo(name: String = "clip.mp4"): Path {
        val target = tempDir.resolve(name)
        val process = ProcessBuilder(
            executable.toString(), "-hide_banner", "-v", "error",
            "-f", "lavfi", "-i", "testsrc=size=64x48:rate=10:duration=1",
            "-c:v", "mpeg4", "-pix_fmt", "yuv420p", "file:$target",
        ).redirectErrorStream(true).start()
        val log = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        assertEquals(0, process.waitFor(), log)
        return target
    }

    private fun assertOneSecond(durationMs: Long?) {
        assertTrue(durationMs != null && durationMs in 900L..1100L, "duration $durationMs")
    }

    @Test
    fun `reads the first frame of a video`() {
        val frame = backend.firstFrame(makeVideo())

        assertNotNull(frame)
        assertEquals(64, frame?.image?.width)
        assertEquals(48, frame?.image?.height)
        assertEquals(64, frame?.metadata?.width)
        assertEquals(48, frame?.metadata?.height)
        assertOneSecond(frame?.metadata?.durationMs)
    }

    @Test
    fun `probes size and duration`() {
        val meta = backend.probe(makeVideo())

        assertEquals(64, meta?.width)
        assertEquals(48, meta?.height)
        assertOneSecond(meta?.durationMs)
    }

    @Test
    fun `decodes a still`() {
        val file = tempDir.resolve("still.png")
        ImageIO.write(BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB), "png", file.toFile())

        val frame = backend.firstFrame(file)

        assertEquals(32, frame?.image?.width)
        assertEquals(16, frame?.image?.height)
    }

    @Test
    fun `a name like an option, with spaces and non-ASCII letters, is just a file`() {
        val video = makeVideo("-y ünïcödé clip.mp4")

        assertEquals(64, backend.firstFrame(video)?.image?.width)
    }

    @Test
    fun `a path longer than 260 characters works`() {
        val video = makeVideo()
        var folder = tempDir
        while (folder.toString().length < 280) folder = folder.resolve("a".repeat(50))
        Files.createDirectories(folder)
        val moved = Files.move(video, folder.resolve("clip.mp4"))

        assertEquals(64, backend.firstFrame(moved)?.image?.width)
        assertEquals(48, backend.probe(moved)?.height)
    }

    @Test
    fun `a file that isn't media fails`() {
        val file = Files.writeString(tempDir.resolve("notes.mp4"), "not a video")

        assertNull(backend.probe(file))
        assertThrows(IOException::class.java) { backend.firstFrame(file) }
    }

    @Test
    fun `a playlist can't make ffmpeg open a URL`() {
        ServerSocket(0).use { server ->
            val playlist = Files.writeString(
                tempDir.resolve("list.m3u8"),
                "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\nhttp://127.0.0.1:${server.localPort}/a.ts\n#EXT-X-ENDLIST\n",
            )

            runCatching { backend.firstFrame(playlist) }

            server.soTimeout = 300
            assertThrows(SocketTimeoutException::class.java) { server.accept().close() }
        }
    }

    @Test
    fun `a playlist can't make ffmpeg open another local file`() {
        val video = makeVideo("private.mp4")
        val playlist = Files.writeString(
            tempDir.resolve("list.m3u8"),
            "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\n${video.toUri()}\n#EXT-X-ENDLIST\n",
        )

        assertNull(backend.probe(playlist))
        assertThrows(IOException::class.java) { backend.firstFrame(playlist) }
    }

    @Test
    fun `a numbered name doesn't read other images`() {
        val file = tempDir.resolve("img%03d.png")
        ImageIO.write(BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB), "png", file.toFile())
        ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", tempDir.resolve("img001.png").toFile())

        // Read as a single PNG or refused, never as the sequence img001.png
        val width = runCatching { backend.firstFrame(file)?.image?.width }.getOrNull()
        assertTrue(width == null || width == 32, "width $width")
    }

    @Test
    fun `a run that takes too long is stopped`() {
        val video = makeVideo()
        val slow = ExternalFfmpegBackend(executable, timeout = Duration.ofMillis(1))

        val e = assertThrows(IOException::class.java) { slow.firstFrame(video) }
        assertTrue(e.message.orEmpty().startsWith("FFmpeg did not finish"), e.message)
    }

    @Test
    fun `version names ffmpeg`() {
        assertTrue(backend.version().startsWith("ffmpeg version"))
    }
}
