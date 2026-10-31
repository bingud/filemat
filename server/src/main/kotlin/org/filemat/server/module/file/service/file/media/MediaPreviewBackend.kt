package org.filemat.server.module.file.service.file.media

import org.filemat.server.module.file.model.FileContentMetadata
import java.awt.image.BufferedImage
import java.nio.file.Path

/**
 * Reads video frames and media metadata with FFmpeg (§7.2).
 * [JavacvBackend] where the bundled natives load (Linux x64 and arm64), [ExternalFfmpegBackend] otherwise.
 */
interface MediaPreviewBackend {
    /**
     * Returns the size and duration of [path], or null when FFmpeg can't read it
     */
    fun probe(path: Path): FileContentMetadata?

    /**
     * Returns the first video frame of [path] (the picture itself for a still), or null when it has none.
     * Throws when FFmpeg fails.
     */
    fun firstFrame(path: Path): MediaFrame?
}

/**
 * The FFmpeg demuxers that may open a file (`format_whitelist`): video containers (`mov` also reads AVIF and HEIC),
 * and single images for the fallback when the image decoder fails.
 * Formats that open other local files are left out, so a user can't get a frame of a file they can't open:
 * playlists and manifests (HLS, DASH, concat, IMF), and `image2`, which reads a numbered sequence for a name like `img%03d.png`.
 * `-protocol_whitelist file` doesn't stop these.
 */
const val ffmpegFormatWhitelist = "mov,mp4,m4a,3gp,3g2,mj2,matroska,webm,avi,mpegts,ogg,flv,asf,mpeg," +
        "png_pipe,jpeg_pipe,webp_pipe,bmp_pipe,tiff_pipe,gif,gif_pipe,jpegxl_pipe,j2k_pipe"

/**
 * A decoded frame and the metadata read while decoding it
 */
class MediaFrame(val image: BufferedImage, val metadata: FileContentMetadata?)

/**
 * Every FFmpeg slot stayed taken for the short wait (R12). It says nothing about the file, so the request answers 503
 * and no preview, metadata or failure is cached for it.
 */
class FfmpegBusyException : RuntimeException(MESSAGE) {
    companion object {
        const val MESSAGE = "Video previews are busy. Try again in a moment."
    }
}
