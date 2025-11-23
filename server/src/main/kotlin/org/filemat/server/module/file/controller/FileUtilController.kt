package org.filemat.server.module.file.controller

import com.drew.metadata.exif.ExifIFD0Directory
import jakarta.servlet.http.HttpServletRequest
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import org.filemat.server.common.State
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.getPrincipal
import org.filemat.server.common.util.resolvePath
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.FileService
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.charset.StandardCharsets
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.min
import org.bytedeco.ffmpeg.global.avutil.*
import org.filemat.server.config.auth.Unauthenticated
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.sharedFiles.service.FileShareService
import java.awt.geom.AffineTransform


@RestController
@RequestMapping("/v1/file")
class FileUtilController(
    private val fileService: FileService,
    private val fileShareService: FileShareService,
    private val entityService: EntityService,
) : AController() {

    init {
        av_log_set_level(AV_LOG_QUIET)
    }

    @Unauthenticated
    @GetMapping("/image-thumbnail")
    fun imageThumbnailMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String,
        @RequestParam("size", required = false) rawSize: String?,
        @RequestParam("shareToken", required = false) shareToken: String?,
    ): ResponseEntity<StreamingResponseBody> {
        val principal = request.getPrincipal()
        val path = FilePath.of(rawPath)
        val size = min(rawSize?.toIntOrNull() ?: 100, 4096)

        val (canonicalPathResult, pathContainsSymlink) = fileService.resolvePathWithOptionalShare(path, shareToken, withPathContainsSymlink = true)
        val canonicalPath = canonicalPathResult.let {
            if (it.notFound) return streamBad("This file was not found.", "")
            if (it.isNotSuccessful) return streamInternal(it.error, "")
            it.value
        }

        val fileContentResult = fileService.getFileContent(
            principal,
            path,
            existingCanonicalPath = canonicalPath,
            existingPathContainsSymlink = pathContainsSymlink,
            ignorePermissions = shareToken != null
        )
        if (fileContentResult.notFound) return streamBad("This file was not found.", "")
        if (fileContentResult.rejected) return streamBad(fileContentResult.error, "")
        if (fileContentResult.isNotSuccessful) return streamInternal(fileContentResult.error, "")

        val filename = path.pathString.substringAfterLast("/") + "_thumb.jpg"

        val responseBody = StreamingResponseBody { outputStream ->
            try {
                fileContentResult.value.use { inputStream ->
                    val bytes = inputStream.readBytes()

                    val orientation = try {
                        val metadata = com.drew.imaging.ImageMetadataReader
                            .readMetadata(java.io.ByteArrayInputStream(bytes))
                        val exifDir = metadata
                            .getFirstDirectoryOfType(
                                ExifIFD0Directory::class.java
                            )
                        exifDir?.getInt(
                            ExifIFD0Directory.TAG_ORIENTATION
                        ) ?: 1
                    } catch (e: Exception) {
                        1
                    }

                    val originalImage = ImageIO
                        .read(java.io.ByteArrayInputStream(bytes))
                    if (originalImage == null) {
                        outputStream.write("Invalid image format".toByteArray())
                        return@StreamingResponseBody
                    }

                    val corrected = applyExifOrientation(originalImage, orientation)
                    val thumbImage = createThumbnail(corrected, size)
                    val finalImage = ensureJpegCompatible(thumbImage)

                    val writers = ImageIO.getImageWritersByFormatName("jpeg")
                    if (!writers.hasNext()) {
                        outputStream.write("No JPEG writer found".toByteArray())
                        return@StreamingResponseBody
                    }

                    val writer = writers.next()
                    val params = writer.defaultWriteParam
                    params.compressionMode = ImageWriteParam.MODE_EXPLICIT
                    params.compressionQuality = 0.4f

                    val ios = ImageIO.createImageOutputStream(outputStream)
                    writer.output = ios
                    writer.write(null, IIOImage(finalImage, null, null), params)
                    writer.dispose()
                    ios.flush()
                    ios.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                try {
                    outputStream.write("Error generating thumbnail".toByteArray())
                } catch (_: Exception) {
                }
            } finally {
                try {
                    outputStream.flush()
                } catch (_: Exception) {
                }
            }
        }

        val cd = ContentDisposition.inline()
            .filename(filename, StandardCharsets.UTF_8)
            .build()

        val headers = HttpHeaders().apply {
            set(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
        }

        return ResponseEntity.ok()
            .headers(headers)
            .contentType(MediaType.IMAGE_JPEG)
            .body(responseBody)
    }

    // Applies EXIF orientation (1..8) in a single AffineTransform mapping.
    private fun applyExifOrientation(img: BufferedImage, orientation: Int): BufferedImage {
        if (orientation == 1) return img

        val w = img.width
        val h = img.height
        val tx = AffineTransform()

        when (orientation) {
            2 -> {
                // flip horizontal
                tx.scale(-1.0, 1.0)
                tx.translate(-w.toDouble(), 0.0)
            }
            3 -> {
                // rotate 180
                tx.translate(w.toDouble(), h.toDouble())
                tx.rotate(Math.PI)
            }
            4 -> {
                // flip vertical
                tx.scale(1.0, -1.0)
                tx.translate(0.0, -h.toDouble())
            }
            5 -> {
                // transpose: rotate 90 CW and flip horizontal
                tx.rotate(Math.PI / 2)
                tx.scale(1.0, -1.0)
                tx.translate(0.0, -h.toDouble())
            }
            6 -> {
                // rotate 90 CW
                tx.translate(h.toDouble(), 0.0)
                tx.rotate(Math.PI / 2)
            }
            7 -> {
                // transverse: rotate 270 CW and flip horizontal
                tx.rotate(-Math.PI / 2)
                tx.scale(1.0, -1.0)
                tx.translate(-h.toDouble(), 0.0)
            }
            8 -> {
                // rotate 270 CW
                tx.translate(0.0, w.toDouble())
                tx.rotate(-Math.PI / 2)
            }
            else -> return img
        }

        val newW = if (orientation in 5..8) h else w
        val newH = if (orientation in 5..8) w else h

        val destType = determineDestType(img)
        val dest = BufferedImage(newW, newH, destType)
        val g = dest.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(img, tx, null)
        g.dispose()
        return dest
    }

    // Decide destination BufferedImage type: keep original type when possible, otherwise
// preserve alpha or fallback to TYPE_INT_RGB.
    private fun determineDestType(img: BufferedImage): Int {
        return if (img.type != BufferedImage.TYPE_CUSTOM && img.type != 0) {
            img.type
        } else if (img.colorModel.hasAlpha()) {
            BufferedImage.TYPE_INT_ARGB
        } else {
            BufferedImage.TYPE_INT_RGB
        }
    }

    // Ensure JPEG-compatible (no alpha) BufferedImage
    private fun ensureJpegCompatible(img: BufferedImage): BufferedImage {
        if (!img.colorModel.hasAlpha()) return img
        val rgb = BufferedImage(img.width, img.height,
            BufferedImage.TYPE_INT_RGB)
        val g = rgb.createGraphics()
        g.drawImage(img, 0, 0, java.awt.Color.WHITE, null)
        g.dispose()
        return rgb
    }


    @Unauthenticated
    @GetMapping("/video-preview")
    fun videoPreviewMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String,
        @RequestParam("size", required = false) rawSize: String?,
        @RequestParam("shareToken", required = false) shareToken: String?,
    ): ResponseEntity<StreamingResponseBody> {
        val principal = request.getPrincipal()
        val path = FilePath.of(rawPath)
        val size = min(rawSize?.toIntOrNull() ?: 100, 4096)

        // Resolve file path
        val (canonicalPathResult, pathContainsSymlink) = fileService.resolvePathWithOptionalShare(path, shareToken, withPathContainsSymlink = true)
        val canonicalPath = canonicalPathResult.let {
            if (it.notFound) return streamBad("This file was not found.", "")
            if (it.isNotSuccessful) return streamInternal(it.error, "")
            it.value
        }

        // Check if symlinks are allowed
        if (!State.App.followSymlinks && pathContainsSymlink) {
            return streamBad("This file is not a video.", "")
        }

        if (shareToken == null) {
            fileService.isAllowedToAccessFile(user = principal, canonicalPath = canonicalPath).let {
                if (it.hasError) return streamInternal(it.error, "")
                if (it.isNotSuccessful) return streamBad(it.errorOrNull ?: "Cannot access this file.", "")
            }
        }

        val filename = path.pathString.substringAfterLast("/") + "_preview.jpg"

        // Create streaming response that handles the video frame extraction process
        val responseBody = StreamingResponseBody { outputStream ->
            try {
                // Use the canonical path directly to access the file
                val videoFile = File(canonicalPath.pathString)

                if (!videoFile.exists() || !videoFile.canRead()) {
                    outputStream.write("File not found or cannot be read".toByteArray())
                    return@StreamingResponseBody
                }

                // Create an FfmpegFrameGrabber directly with the file
                val grabber = FFmpegFrameGrabber(videoFile)
                grabber.start()

                try {
                    // Grab first frame
                    val frame = grabber.grabImage()

                    if (frame == null) {
                        outputStream.write("Could not extract frame from video".toByteArray())
                        return@StreamingResponseBody
                    }

                    // Convert frame to BufferedImage
                    val converter = Java2DFrameConverter()
                    val originalImage = converter.convert(frame)

                    if (originalImage == null) {
                        outputStream.write("Invalid video format or frame".toByteArray())
                        return@StreamingResponseBody
                    }

                    // Calculate dimensions
                    val thumbImage = createThumbnail(originalImage, size)

                    // Write compressed image directly to the output stream
                    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
                    val params = writer.defaultWriteParam
                    params.compressionMode = ImageWriteParam.MODE_EXPLICIT
                    params.compressionQuality = 0.4f // High compression

                    val imageOutputStream = ImageIO.createImageOutputStream(outputStream)
                    writer.output = imageOutputStream
                    writer.write(null, IIOImage(thumbImage, null, null), params)
                    writer.dispose()
                    imageOutputStream.flush()
                    imageOutputStream.close()
                } finally {
                    // Always stop and release the grabber
                    grabber.stop()
                    grabber.release()
                }
            } catch (e: Exception) {
                // Log the error - proper error handling in a streaming response
                e.printStackTrace()
                // Write a simple error message to the output
                outputStream.write("Error generating video preview".toByteArray())
            } finally {
                // Ensure output stream is properly flushed and closed
                try {
                    outputStream.flush()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // Set response display type to inline (can be displayed in browser)
        val cd = ContentDisposition.inline()
            .filename(filename, StandardCharsets.UTF_8)
            .build()

        // Remove Content-Length header since we don't know the exact size
        val headers = HttpHeaders().apply {
            set(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
        }

        return ResponseEntity.ok()
            .headers(headers)
            .contentType(MediaType.IMAGE_JPEG)
            .body(responseBody)
    }


    private fun createThumbnail(originalImage: BufferedImage, size: Int): BufferedImage {
        val width = originalImage.width
        val height = originalImage.height
        val ratio = width.toDouble() / height.toDouble()

        val thumbWidth: Int
        val thumbHeight: Int

        if (width > height) {
            thumbWidth = size
            thumbHeight = (size / ratio).toInt().coerceAtLeast(1)
        } else {
            thumbHeight = size
            thumbWidth = (size * ratio).toInt().coerceAtLeast(1)
        }

        val thumbImage = BufferedImage(thumbWidth, thumbHeight, BufferedImage.TYPE_INT_RGB)
        val graphics2D = thumbImage.createGraphics()
        graphics2D.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics2D.drawImage(originalImage, 0, 0, thumbWidth, thumbHeight, null)
        graphics2D.dispose()

        return thumbImage
    }
}
