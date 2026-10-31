package org.filemat.server.module.file.service.file.media

import org.bytedeco.ffmpeg.global.avutil.AV_LOG_QUIET
import org.bytedeco.ffmpeg.global.avutil.av_log_set_level

/**
 * Loads the FFmpeg natives once.
 *
 * A missing or broken native disables FFmpeg features (video previews, FFmpeg image fallback, media metadata)
 * instead of failing application startup.
 */
object FfmpegSupport {
    /**
     * Error from loading the natives, or null when FFmpeg is usable.
     */
    val loadError: Throwable? by lazy {
        runCatching { av_log_set_level(AV_LOG_QUIET) }.exceptionOrNull()
    }

    val isAvailable: Boolean
        get() = loadError == null
}
