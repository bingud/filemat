package org.filemat.server.module.file.controller

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


@RestController
@RequestMapping("/v1/file")
class FileUtilController(
    private val fileService: FileService,
) : AController() {

    init {
        av_log_set_level(AV_LOG_QUIET)
    }

    @GetMapping("/image-thumbnail")
    fun imageThumbnailMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String,
        @RequestParam("size", required = false) rawSize: String?,
    ): ResponseEntity<StreamingResponseBody> {
        val principal = request.getPrincipal()!!
        val path = FilePath.of(rawPath)
        val size = min(rawSize?.toIntOrNull() ?: 100, 4096)

        // Resolve file path
        val (pathResult, pathContainsSymlink) = resolvePath(path)
        val canonicalPath = pathResult.let {
            if (it.notFound) return streamBad("This file was not found.", "")
            if (it.isNotSuccessful) return streamInternal(it.error, "")
            it.value
        }

        // Get the file content
        val fileContentResult = fileService.getFileContent(principal, path, existingCanonicalPath = canonicalPath)
        if (fileContentResult.notFound) return streamBad("This file was not found.", "")
        if (fileContentResult.rejected) return streamBad(fileContentResult.error, "")
        if (fileContentResult.isNotSuccessful) return streamInternal(fileContentResult.error, "")

        val filename = path.pathString.substringAfterLast("/") + "_thumb.jpg"

        // Create streaming response that handles the entire thumbnail generation process
        val responseBody = StreamingResponseBody { outputStream ->
            try {
                // Use try-with-resources to ensure proper closure of input stream
                fileContentResult.value.use { inputStream ->
                    val originalImage = ImageIO.read(inputStream)

                    if (originalImage == null) {
                        outputStream.write("Invalid image format".toByteArray())
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
                }
            } catch (e: Exception) {
                // Log the error - proper error handling in a streaming response
                e.printStackTrace()
                // Write a simple error message to the output
                outputStream.write("Error generating thumbnail".toByteArray())
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


    @GetMapping("/video-preview")
    fun videoPreviewMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String,
        @RequestParam("size", required = false) rawSize: String?,
    ): ResponseEntity<StreamingResponseBody> {
        val principal = request.getPrincipal()!!
        val path = FilePath.of(rawPath)
        val size = min(rawSize?.toIntOrNull() ?: 100, 4096)

        // Resolve file path
        val (pathResult, pathContainsSymlink) = resolvePath(path)
        val canonicalPath = pathResult.let {
            if (it.notFound) return streamBad("This file was not found.", "")
            if (it.isNotSuccessful) return streamInternal(it.error, "")
            it.value
        }

        // Check if symlinks are allowed
        if (!State.App.followSymlinks && pathContainsSymlink) {
            return streamBad("This file is not a video.", "")
        }

        fileService.isAllowedToAccessFile(user = principal, canonicalPath = canonicalPath).let {
            if (it.hasError) return streamInternal(it.error, "")
            if (it.isNotSuccessful) return streamBad(it.errorOrNull ?: "Cannot access this file.", "")
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