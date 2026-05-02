package org.filemat.server.module.file.controller

import jakarta.servlet.http.HttpServletRequest
import org.filemat.server.common.util.RateLimitedLog
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.getPrincipal
import org.filemat.server.config.auth.Unauthenticated
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.file.ThumbnailService
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.model.UserAction
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.math.min

@RestController
@RequestMapping("/v1/file")
class FileUtilController(
    private val fileService: FileService,
    private val thumbnailService: ThumbnailService,
    private val logService: LogService,
) : AController() {

    @Unauthenticated
    @GetMapping("/image-thumbnail")
    fun imageThumbnailMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String,
        @RequestParam("size", required = false) rawSize: String?,
        @RequestParam("shareToken", required = false) shareToken: String?,
    ): ResponseEntity<*> {
        val principal = request.getPrincipal()
        val path = FilePath.of(rawPath)
        val targetSize = min(rawSize?.toIntOrNull() ?: 100, 4096)
        val ignorePermissions = shareToken != null

        val canonicalPathResult = fileService.resolvePathWithOptionalShare(path, shareToken, withPathContainsSymlink = true)
        val canonicalPath = canonicalPathResult.let {
            if (it.notFound) return streamBad("This file was not found.", "")
            if (it.isNotSuccessful) return streamInternal(it.error, "")
            it.value
        }

        val meta = fileService.getMetadata(principal, canonicalPath, true, ignorePermissions = ignorePermissions).let {
            if (it.isNotSuccessful) return streamInternal(it.errorOrNull ?: "Failed to get file metadata to generate a preview.")
            it.value
        }

        val filename = path.pathString.substringAfterLast("/") + "_${targetSize}p.jpg"
        val cd = ContentDisposition.inline()
            .filename(filename, StandardCharsets.UTF_8)
            .build()

        val headers = HttpHeaders().apply {
            set(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
        }

        val baos = ByteArrayOutputStream(512 * 1024)
        try {
            thumbnailService.streamImageThumbnail(
                canonicalPathString = canonicalPath.pathString,
                targetSize = targetSize,
                modifiedDate = meta.modifiedDate,
                fileSize = meta.size,
                inputStreamProvider = {
                    val fileContentResult = fileService.getFileContent(
                        principal,
                        path,
                        existingCanonicalPath = canonicalPath,
                        ignorePermissions = ignorePermissions
                    )
                    if (fileContentResult.isNotSuccessful) {
                        throw Exception(fileContentResult.errorOrNull ?: "Cannot access file content")
                    }
                    fileContentResult.value
                },
                outputStream = baos,
            )
        } catch (e: Exception) {
            RateLimitedLog.ifDue("file_util_image_thumbnail") {
                logService.error(
                    type = LogType.SYSTEM,
                    action = UserAction.NONE,
                    description = "Image thumbnail generation failed.",
                    message = e.stackTraceToString(),
                )
            }
            return streamInternal("Error generating thumbnail", "")
        }

        val bytes = baos.toByteArray()
        if (bytes.isEmpty()) {
            RateLimitedLog.ifDue("file_util_image_thumbnail_empty") {
                logService.error(
                    type = LogType.SYSTEM,
                    action = UserAction.NONE,
                    description = "Image thumbnail generation produced no bytes.",
                    message = "",
                )
            }
            return streamInternal("Error generating thumbnail", "")
        }

        return ResponseEntity.ok()
            .headers(headers)
            .contentLength(bytes.size.toLong())
            .contentType(MediaType.IMAGE_JPEG)
            .body(bytes)
    }

    @Unauthenticated
    @GetMapping("/video-preview")
    fun videoPreviewMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String,
        @RequestParam("size", required = false) rawSize: String?,
        @RequestParam("shareToken", required = false) shareToken: String?,
    ): ResponseEntity<*> {
        val principal = request.getPrincipal()
        val path = FilePath.of(rawPath)
        val targetSize = min(rawSize?.toIntOrNull() ?: 100, 4096)

        val canonicalPathResult = fileService.resolvePathWithOptionalShare(path, shareToken, withPathContainsSymlink = true)
        val canonicalPath = canonicalPathResult.let {
            if (it.notFound) return streamBad("This file was not found.", "")
            if (it.isNotSuccessful) return streamInternal(it.error, "")
            it.value
        }

        val ignorePermissions = shareToken != null
        fileService.isAllowedToAccessFile(
            user = principal,
            canonicalPath = canonicalPath,
            ignorePermissions = ignorePermissions,
        ).let {
            if (it.hasError) return streamInternal(it.error, "")
            if (it.isNotSuccessful) return streamBad(it.errorOrNull ?: "Cannot access this file.", "")
        }

        val videoFile = File(canonicalPath.pathString)
        val modifiedDate = videoFile.lastModified()
        val fileSize = videoFile.length()

        val filename = path.pathString.substringAfterLast("/") + "_preview.jpg"
        val cd = ContentDisposition.inline()
            .filename(filename, StandardCharsets.UTF_8)
            .build()

        val headers = HttpHeaders().apply {
            set(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
        }

        val baos = ByteArrayOutputStream(512 * 1024)
        try {
            thumbnailService.streamVideoPreview(
                canonicalPathString = canonicalPath.pathString,
                targetSize = targetSize,
                modifiedDate = modifiedDate,
                fileSize = fileSize,
                outputStream = baos,
            )
        } catch (e: Exception) {
            RateLimitedLog.ifDue("file_util_video_preview") {
                logService.error(
                    type = LogType.SYSTEM,
                    action = UserAction.NONE,
                    description = "Video preview generation failed.",
                    message = e.stackTraceToString(),
                )
            }
            return streamInternal("Error generating video preview", "")
        }

        val bytes = baos.toByteArray()
        if (bytes.isEmpty()) {
            RateLimitedLog.ifDue("file_util_video_preview_empty") {
                logService.error(
                    type = LogType.SYSTEM,
                    action = UserAction.NONE,
                    description = "Video preview generation produced no bytes.",
                    message = "",
                )
            }
            return streamInternal("Error generating video preview", "")
        }

        return ResponseEntity.ok()
            .headers(headers)
            .contentLength(bytes.size.toLong())
            .contentType(MediaType.IMAGE_JPEG)
            .body(bytes)
    }
}
