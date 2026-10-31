package org.filemat.server.module.file.service.file.media

import org.filemat.server.common.State
import org.filemat.server.config.platform.Platform
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.model.UserAction
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

/**
 * Which FFmpeg video previews and media metadata use, or [problem] when there is none (Q47)
 */
class MediaPreviewStatus(
    val backend: MediaPreviewBackend? = null,
    /** "built-in", or the path of the ffmpeg executable */
    val source: String? = null,
    val version: String? = null,
    val problem: String? = null,
)

/**
 * Picks the FFmpeg backend once (§7.2): the bundled javacv natives when they load,
 * otherwise `ffmpeg` from `FM_FFMPEG_PATH`, otherwise from `PATH`.
 */
@Service
class MediaPreviewService(
    private val state: State,
    private val logService: LogService,
) {
    val status: MediaPreviewStatus by lazy {
        detect(
            javacvAvailable = FfmpegSupport.isAvailable,
            configuredPath = state.app.ffmpegPath,
            // Looked up without case, since Windows names it "Path"
            searchPath = System.getenv("PATH"),
        )
    }

    /**
     * Null when video previews are off
     */
    val backend: MediaPreviewBackend? get() = status.backend

    // After startup, because the log table does not exist until the migrations have run
    @EventListener(ApplicationReadyEvent::class)
    fun logStatusWhenReady() = logStatus(status)

    internal fun logStatus(status: MediaPreviewStatus) {
        if (status.problem != null) {
            logService.warn(
                type = LogType.SYSTEM,
                action = UserAction.NONE,
                description = "Video previews are off. ${status.problem}",
                message = FfmpegSupport.loadError?.stackTraceToString() ?: "",
            )
        } else if (status.backend is ExternalFfmpegBackend) {
            logService.info(
                type = LogType.SYSTEM,
                action = UserAction.NONE,
                description = "Video previews use FFmpeg at ${status.source}.",
                message = status.version ?: "",
            )
        }
    }

    companion object {
        internal fun detect(javacvAvailable: Boolean, configuredPath: String?, searchPath: String?): MediaPreviewStatus {
            if (javacvAvailable) return MediaPreviewStatus(backend = JavacvBackend, source = "built-in")

            val executable = if (configuredPath != null) {
                FfmpegLocator.fromSetting(configuredPath)
                    ?: return MediaPreviewStatus(problem = "FM_FFMPEG_PATH ($configuredPath) is not the full path of an ffmpeg executable.")
            } else {
                FfmpegLocator.fromSearchPath(searchPath)
                    ?: return MediaPreviewStatus(problem = "FFmpeg was not found. Install it and add it to PATH, or set FM_FFMPEG_PATH, then restart Filemat.")
            }

            val backend = ExternalFfmpegBackend(executable)
            val version = try {
                backend.version()
            } catch (e: Exception) {
                return MediaPreviewStatus(problem = "$executable could not be run: ${e.message}")
            }
            return MediaPreviewStatus(backend = backend, source = executable.toString(), version = version)
        }
    }
}

/**
 * Finds the ffmpeg executable
 */
internal object FfmpegLocator {
    private val fileName = if (Platform.isWindows) "ffmpeg.exe" else "ffmpeg"

    /**
     * `FM_FFMPEG_PATH`: the full path of the executable
     */
    fun fromSetting(value: String): Path? {
        val path = runCatching { Path.of(unquote(value)) }.getOrNull() ?: return null
        return path.takeIf { isUsable(it) }
    }

    /**
     * The first ffmpeg in the folders of [searchPath]
     */
    fun fromSearchPath(searchPath: String?): Path? {
        return searchPath.orEmpty()
            .split(Platform.listSeparator)
            .map { unquote(it) }
            .filter { it.isNotEmpty() }
            .mapNotNull { runCatching { Path.of(it).resolve(fileName) }.getOrNull() }
            .firstOrNull { isUsable(it) }
    }

    private fun isUsable(path: Path): Boolean {
        // A relative path would depend on the working folder
        if (!path.isAbsolute) return false
        // Windows runs .cmd and .bat files through cmd.exe, which parses the arguments again
        if (Platform.isWindows && !path.fileName.toString().endsWith(".exe", ignoreCase = true)) return false
        return Files.isRegularFile(path) && Files.isExecutable(path)
    }

    private fun unquote(value: String) = value.trim().removeSurrounding("\"")
}
