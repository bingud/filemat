package org.filemat.server.module.file.service.file.media

import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.common.State
import org.filemat.server.config.platform.Platform
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class MediaPreviewServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val logService = mockk<LogService>(relaxed = true)
    private val service = MediaPreviewService(State(env = emptyMap()), logService)

    private val executableName = if (Platform.isWindows) "ffmpeg.exe" else "ffmpeg"

    /**
     * A file named like ffmpeg that isn't a program
     */
    private fun fakeFfmpeg(folder: Path, name: String = executableName): Path {
        Files.createDirectories(folder)
        val file = Files.writeString(folder.resolve(name), "not a program")
        file.toFile().setExecutable(true)
        return file
    }

    private fun detect(configuredPath: String? = null, searchPath: String? = null, javacvAvailable: Boolean = false) =
        MediaPreviewService.detect(javacvAvailable, configuredPath, searchPath)

    @Test
    fun `the bundled natives come first`() {
        val status = detect(searchPath = fakeFfmpeg(tempDir).parent.toString(), javacvAvailable = true)

        assertSame(JavacvBackend, status.backend)
        assertEquals("built-in", status.source)
        assertNull(status.problem)
    }

    @Test
    fun `without FFmpeg, video previews are off`() {
        val status = detect(searchPath = tempDir.toString())

        assertNull(status.backend)
        assertTrue(status.problem.orEmpty().startsWith("FFmpeg was not found."))
    }

    @Test
    fun `FM_FFMPEG_PATH is used instead of PATH`() {
        val missing = tempDir.resolve("missing").resolve(executableName).toString()
        val onPath = fakeFfmpeg(tempDir.resolve("bin"))

        val status = detect(configuredPath = missing, searchPath = onPath.parent.toString())

        assertNull(status.backend)
        assertTrue(status.problem.orEmpty().startsWith("FM_FFMPEG_PATH ($missing)"))
    }

    @Test
    fun `a file that isn't ffmpeg is reported`() {
        val fake = fakeFfmpeg(tempDir)

        val status = detect(configuredPath = "\"$fake\"")

        assertNull(status.backend)
        assertTrue(status.problem.orEmpty().startsWith("$fake could not be run"))
    }

    @Test
    fun `the first PATH folder with ffmpeg wins, and quoted or empty entries are fine`() {
        val first = fakeFfmpeg(tempDir.resolve("first"))
        fakeFfmpeg(tempDir.resolve("second"))
        val separator = Platform.listSeparator
        val searchPath = listOf(tempDir.resolve("empty"), "", "\"${first.parent}\"", tempDir.resolve("second")).joinToString(separator)

        assertEquals(first, FfmpegLocator.fromSearchPath(searchPath))
    }

    @Test
    fun `relative paths are never used`() {
        assertNull(FfmpegLocator.fromSetting(executableName))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `batch files are never run`() {
        val batch = fakeFfmpeg(tempDir, "ffmpeg.cmd")

        assertNull(FfmpegLocator.fromSetting(batch.toString()))
    }

    @Test
    fun `ffmpeg on PATH is used where the natives don't load`() {
        val ffmpeg = FfmpegLocator.fromSearchPath(System.getenv("PATH"))
        assumeTrue(ffmpeg != null, "ffmpeg is not on PATH")

        val status = detect(searchPath = System.getenv("PATH"))

        assertTrue(status.backend is ExternalFfmpegBackend)
        assertEquals(ffmpeg.toString(), status.source)
        assertTrue(status.version.orEmpty().startsWith("ffmpeg version"))
    }

    @Test
    fun `creating the service runs and logs nothing`() {
        MediaPreviewService(State(env = emptyMap()), logService)

        verify(exactly = 0) { logService.warn(any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { logService.info(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a missing FFmpeg is a warning`() {
        service.logStatus(MediaPreviewStatus(problem = "FFmpeg was not found."))

        verify(exactly = 1) {
            logService.warn(LogType.SYSTEM, any(), "Video previews are off. FFmpeg was not found.", any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `an external FFmpeg is logged with its path`() {
        val backend = ExternalFfmpegBackend(tempDir.resolve(executableName))
        service.logStatus(MediaPreviewStatus(backend = backend, source = "C:\\ffmpeg\\ffmpeg.exe", version = "ffmpeg version 7.1"))

        verify(exactly = 1) {
            logService.info(LogType.SYSTEM, any(), "Video previews use FFmpeg at C:\\ffmpeg\\ffmpeg.exe.", "ffmpeg version 7.1", any(), any(), any(), any())
        }
    }

    @Test
    fun `the bundled natives log nothing`() {
        service.logStatus(MediaPreviewStatus(backend = JavacvBackend, source = "built-in"))

        verify(exactly = 0) { logService.warn(any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { logService.info(any(), any(), any(), any(), any(), any(), any(), any()) }
    }
}
