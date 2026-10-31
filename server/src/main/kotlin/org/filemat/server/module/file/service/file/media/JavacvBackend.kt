package org.filemat.server.module.file.service.file.media

import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import org.filemat.server.module.file.model.FileContentMetadata
import java.nio.file.Path

/**
 * FFmpeg through the javacv natives bundled in the JAR. Only usable when [FfmpegSupport.isAvailable].
 */
object JavacvBackend : MediaPreviewBackend {

    override fun probe(path: Path): FileContentMetadata? {
        return try {
            withGrabber(path) { metadataOf(it) }
        } catch (_: Throwable) {
            null
        }
    }

    override fun firstFrame(path: Path): MediaFrame? = withGrabber(path) { grabber ->
        val metadata = metadataOf(grabber)
        val frame = grabber.grabImage() ?: return@withGrabber null
        // The converter copies the frame, so the image outlives the grabber
        val image = Java2DFrameConverter().convert(frame) ?: return@withGrabber null
        MediaFrame(image, metadata)
    }

    private fun <T> withGrabber(path: Path, action: (FFmpegFrameGrabber) -> T): T {
        val grabber = FFmpegFrameGrabber(path.toFile())
        try {
            grabber.setOption("probesize", "524288")
            grabber.setOption("analyzeduration", "1000000")
            // Files that FFmpeg reads as playlists may only open local files (§7.3)
            grabber.setOption("protocol_whitelist", "file")
            // Playlists aren't opened at all: they can list another user's files
            grabber.setOption("format_whitelist", ffmpegFormatWhitelist)
            grabber.start()
            return action(grabber)
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }
    }

    private fun metadataOf(grabber: FFmpegFrameGrabber): FileContentMetadata? {
        val width = grabber.imageWidth.takeIf { it > 0 }
        val height = grabber.imageHeight.takeIf { it > 0 }
        // FFmpeg lengthInTime is microseconds.
        val durationMs = grabber.lengthInTime.takeIf { it > 0 }?.div(1000)
        if (width == null && height == null && durationMs == null) return null
        return FileContentMetadata(width = width, height = height, durationMs = durationMs)
    }
}
