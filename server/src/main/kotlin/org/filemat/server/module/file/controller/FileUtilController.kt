package org.filemat.server.module.file.controller

import jakarta.servlet.http.HttpServletRequest
import net.coobird.thumbnailator.Thumbnails
import net.coobird.thumbnailator.resizers.configurations.ScalingMode
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.getPrincipal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.file.FileService
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.math.min
import org.bytedeco.ffmpeg.global.avutil.*
import org.filemat.server.config.auth.Unauthenticated
import kotlin.math.max


@RestController
@RequestMapping("/v1/file")
class FileUtilController(
    private val fileService: FileService,
) : AController() {

    init {
        av_log_set_level(AV_LOG_QUIET)
    }

    fun calculateQuality(width: Int): Double {
        val minWidth = 200.0
        val maxWidth = 1150.0
        val maxQuality = 1.0
        val minQuality = 0.60

        if (width <= minWidth) return maxQuality
        if (width >= maxWidth) return minQuality

        val progress = (width - minWidth) / (maxWidth - minWidth)
        val quality = maxQuality - (progress * (maxQuality - minQuality))

        return Math.round(quality * 100.0) / 100.0
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
        val targetSize = min(rawSize?.toIntOrNull() ?: 100, 4096)

        val canonicalPathResult  = fileService.resolvePathWithOptionalShare(path, shareToken, withPathContainsSymlink = true)
        val canonicalPath = canonicalPathResult.let {
            if (it.notFound) return streamBad("This file was not found.", "")
            if (it.isNotSuccessful) return streamInternal(it.error, "")
            it.value
        }

        val fileContentResult = fileService.getFileContent(
            principal,
            path,
            existingCanonicalPath = canonicalPath,
            ignorePermissions = shareToken != null
        )
        if (fileContentResult.notFound) return streamBad("This file was not found.", "")
        if (fileContentResult.rejected) return streamBad(fileContentResult.error, "")
        if (fileContentResult.isNotSuccessful) return streamInternal(fileContentResult.error, "")

        val filename = path.pathString.substringAfterLast("/") + "_${targetSize}p.jpg"

        val responseBody = StreamingResponseBody { outputStream ->
            try {
                fileContentResult.value.use { inputStream ->
                    try {
                        // Read into memory (required regardless of approach)
                        val image = Thumbnails.of(inputStream)
                            .scale(1.0)
                            .asBufferedImage()
                        val sourceSize = max(image.width, image.height)
                        val finalSize = min(sourceSize, targetSize)

                        val thumbnail = Thumbnails.of(image)
                            .outputFormat("jpg")
                            .outputQuality(calculateQuality(finalSize))

                        // Only apply size reduction if the image exceeds the bounds
                        if (sourceSize > targetSize) {
                            thumbnail.size(targetSize, targetSize).keepAspectRatio(true)
                        } else {
                            // Do not resize, just compress and convert format
                            thumbnail.scale(1.0)
                        }

                        thumbnail.toOutputStream(outputStream)
                    } catch (e: Exception) {
                        // Fallback to ffmpeg for unsupported formats like AVIF/HEIC
                        val imageFile = File(canonicalPath.pathString)

                        val grabber = FFmpegFrameGrabber(imageFile)
                        grabber.start()

                        try {
                            val frame = grabber.grabImage()
                            if (frame == null) {
                                throw Exception("Could not decode image")
                            }

                            val converter = Java2DFrameConverter()
                            val image = converter.convert(frame)

                            if (image == null) {
                                throw Exception("Could not convert frame to image")
                            }
                            val sourceSize = max(image.width, image.height)
                            val finalSize = min(sourceSize, targetSize)

                            val thumbnail = Thumbnails.of(image)
                                .outputFormat("jpg")
                                .outputQuality(calculateQuality(finalSize))
                                .scalingMode(ScalingMode.BILINEAR)

                            // Only apply size reduction if the image exceeds the bounds
                            if (sourceSize > targetSize) {
                                thumbnail.size(targetSize, targetSize).keepAspectRatio(true)
                            } else {
                                // Do not resize, just compress and convert format
                                thumbnail.scale(1.0)
                            }

                            thumbnail.toOutputStream(outputStream)
                        } finally {
                            grabber.stop()
                            grabber.release()
                        }
                    }
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
        val targetSize = min(rawSize?.toIntOrNull() ?: 100, 4096)

        val canonicalPathResult = fileService.resolvePathWithOptionalShare(path, shareToken, withPathContainsSymlink = true)
        val canonicalPath = canonicalPathResult.let {
            if (it.notFound) return streamBad("This file was not found.", "")
            if (it.isNotSuccessful) return streamInternal(it.error, "")
            it.value
        }

        if (shareToken == null) {
            fileService.isAllowedToAccessFile(user = principal, canonicalPath = canonicalPath).let {
                if (it.hasError) return streamInternal(it.error, "")
                if (it.isNotSuccessful) return streamBad(it.errorOrNull ?: "Cannot access this file.", "")
            }
        }

        val filename = path.pathString.substringAfterLast("/") + "_preview.jpg"

        val responseBody = StreamingResponseBody { outputStream ->
            try {
                val videoFile = File(canonicalPath.pathString)

                if (!videoFile.exists() || !videoFile.canRead()) {
                    outputStream.write("File not found or cannot be read".toByteArray())
                    return@StreamingResponseBody
                }

                val grabber = FFmpegFrameGrabber(videoFile)
                grabber.start()

                try {
                    val frame = grabber.grabImage()

                    if (frame == null) {
                        outputStream.write("Could not extract frame from video".toByteArray())
                        return@StreamingResponseBody
                    }

                    val converter = Java2DFrameConverter()
                    val originalImage = converter.convert(frame)

                    if (originalImage == null) {
                        outputStream.write("Invalid video format or frame".toByteArray())
                        return@StreamingResponseBody
                    }
                    val sourceSize = max(originalImage.width, originalImage.height)
                    val finalSize = min(sourceSize, targetSize)

                    val thumbnail = Thumbnails.of(originalImage)
                        .outputFormat("jpg")
                        .outputQuality(calculateQuality(finalSize))

                    // Only apply size reduction if the frame exceeds the bounds
                    if (sourceSize > targetSize) {
                        thumbnail.size(targetSize, targetSize)
                    } else {
                        // Do not resize, just compress and convert format
                        thumbnail.scale(1.0)
                    }

                    thumbnail.toOutputStream(outputStream)
                } finally {
                    grabber.stop()
                    grabber.release()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                outputStream.write("Error generating video preview".toByteArray())
            } finally {
                try {
                    outputStream.flush()
                } catch (e: Exception) {
                    e.printStackTrace()
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
}