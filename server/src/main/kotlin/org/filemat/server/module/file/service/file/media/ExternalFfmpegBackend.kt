package org.filemat.server.module.file.service.file.media

import org.filemat.server.config.platform.Platform
import org.filemat.server.config.platform.WindowsNative
import org.filemat.server.module.file.model.FileContentMetadata
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Runs an `ffmpeg` executable, where the bundled natives don't load (Windows, §7.2).
 *
 * Hardening (§7.3):
 * - No shell. The arguments are a list, and the input is `file:<absolute path>`, so a file name is never read as an option or a protocol.
 * - Only video containers are opened ([ffmpegFormatWhitelist]), not playlists, and only local files.
 * - A run is killed after [timeout], and at most [maxProcesses] run at once.
 *   A request waits [slotWait] for a slot, then gets [FfmpegBusyException], so request threads aren't held up.
 */
class ExternalFfmpegBackend(
    val executable: Path,
    private val timeout: Duration = Duration.ofSeconds(30),
    maxProcesses: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4),
    private val slotWait: Duration = Duration.ofSeconds(2),
) : MediaPreviewBackend {

    // Internal so tests can take every slot
    internal val slots = Semaphore(maxProcesses)

    override fun probe(path: Path): FileContentMetadata? {
        // Without an output, ffmpeg prints the input's details and exits with an error
        val run = try {
            run(inputArgs(path))
        } catch (e: FfmpegBusyException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return FfmpegOutput.metadata(run.log)
    }

    override fun firstFrame(path: Path): MediaFrame? {
        val run = run(inputArgs(path) + listOf("-map", "0:v:0", "-frames:v", "1", "-f", "image2pipe", "-c:v", "bmp", "pipe:1"))
        if (run.exitCode != 0 || run.output.isEmpty()) {
            throw IOException("FFmpeg exited with code ${run.exitCode}: ${run.log.takeLast(2000)}")
        }
        val image = ImageIO.read(ByteArrayInputStream(run.output)) ?: return null
        val metadata = FfmpegOutput.metadata(run.log) ?: FileContentMetadata(width = image.width, height = image.height)
        return MediaFrame(image, metadata)
    }

    /**
     * Returns the version line of `ffmpeg -version`, without the copyright. Throws when [executable] isn't a working ffmpeg.
     */
    fun version(): String {
        val run = run(listOf("-version"))
        val firstLine = run.output.toString(Charsets.UTF_8).lineSequence().first().trim()
        if (run.exitCode != 0 || !firstLine.startsWith("ffmpeg version")) {
            throw IOException("It is not an ffmpeg executable.")
        }
        return firstLine.substringBefore(" Copyright")
    }

    private fun inputArgs(path: Path) = listOf(
        "-hide_banner", "-nostdin", "-v", "info",
        // Same frame orientation and size as javacv
        "-noautorotate",
        "-probesize", "524288", "-analyzeduration", "1000000",
        "-protocol_whitelist", "file",
        // Playlists aren't opened at all: they can list another user's files
        "-format_whitelist", ffmpegFormatWhitelist,
        "-i", "file:${inputPath(path)}",
    )

    /**
     * FFmpeg 5.1 and later add the long path prefix themselves, older builds don't (Q43)
     */
    private fun inputPath(path: Path): String {
        val text = path.toAbsolutePath().toString()
        if (!Platform.isWindows) return text
        return WindowsNative.withLongPathPrefix(text, WindowsNative.filePathLimit)
    }

    private class Run(val exitCode: Int, val output: ByteArray, val log: String)

    private fun run(args: List<String>): Run {
        if (!slots.tryAcquire(slotWait.toMillis(), TimeUnit.MILLISECONDS)) throw FfmpegBusyException()
        try {
            val process = ProcessBuilder(listOf(executable.toString()) + args).start()
            try {
                // A thumbnail shouldn't slow down the user's other programs. If it fails, FFmpeg runs at normal priority.
                if (Platform.isWindows) WindowsNative.lowerPriority(process.pid())
                process.outputStream.close()
                // Both pipes are drained while the process runs, or it blocks once one is full
                val output = readAsync(process.inputStream, maxOutputBytes)
                val log = readAsync(process.errorStream, maxLogBytes)
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw IOException("FFmpeg did not finish within ${timeout.toSeconds()} seconds.")
                }
                return Run(
                    exitCode = process.exitValue(),
                    output = output.get(pipeTimeoutSeconds, TimeUnit.SECONDS),
                    log = log.get(pipeTimeoutSeconds, TimeUnit.SECONDS).toString(Charsets.UTF_8),
                )
            } finally {
                kill(process)
            }
        } finally {
            slots.release()
        }
    }

    private fun kill(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    companion object {
        // An 8192 × 8192 frame as a 24-bit BMP is about 200 MB
        private const val maxOutputBytes = 256 * 1024 * 1024
        // The input's details come first
        private const val maxLogBytes = 64 * 1024
        private const val pipeTimeoutSeconds = 5L

        private val readers = Executors.newVirtualThreadPerTaskExecutor()

        /**
         * Reads up to [limit] bytes and discards the rest, so the process never waits on a full pipe
         */
        private fun readAsync(input: InputStream, limit: Int): Future<ByteArray> = readers.submit<ByteArray> {
            input.use {
                val bytes = it.readNBytes(limit)
                it.transferTo(OutputStream.nullOutputStream())
                bytes
            }
        }
    }
}

/**
 * Reads media metadata from what ffmpeg prints about its input. The format is the same in every locale.
 */
internal object FfmpegOutput {
    private val durationPattern = Regex("""Duration: (\d+):(\d{2}):(\d{2})\.(\d+)""")
    // ", 1920x1080 [SAR 1:1 DAR 16:9]". Codec tags such as "0x31637661" don't follow a comma.
    private val sizePattern = Regex("""(?<=, )(\d{1,5})x(\d{1,5})(?![0-9A-Za-z])""")

    fun metadata(log: String): FileContentMetadata? {
        // The output's stream lines come after the input's
        val input = log.substringBefore("\nOutput #").substringBefore("\nStream mapping:")

        val durationMs = durationPattern.find(input)?.destructured?.let { (hours, minutes, seconds, fraction) ->
            (hours.toLong() * 3600 + minutes.toLong() * 60 + seconds.toLong()) * 1000 + fraction.padEnd(3, '0').take(3).toLong()
        }?.takeIf { it > 0 }

        val size = input.lineSequence()
            .filter { "Stream #" in it && ": Video: " in it }
            .firstNotNullOfOrNull { sizePattern.find(it) }
            ?.destructured
            ?.let { (width, height) -> width.toInt() to height.toInt() }
            ?.takeIf { (width, height) -> width > 0 && height > 0 }

        if (size == null && durationMs == null) return null
        return FileContentMetadata(width = size?.first, height = size?.second, durationMs = durationMs)
    }
}
