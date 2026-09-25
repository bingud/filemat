package org.filemat.server.module.file.controller

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.util.FolderSize
import org.filemat.server.common.util.JsonNonNull
import org.filemat.server.common.util.resolvePath
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.controller.ErrorResponse
import org.filemat.server.common.util.getPrincipal
import org.filemat.server.common.util.json
import org.filemat.server.config.auth.Unauthenticated
import org.filemat.server.module.file.model.AbstractFileMetadata
import org.filemat.server.module.file.model.FileMetadata
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FullFileMetadata
import org.filemat.server.module.file.service.file.FileService
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlin.time.Duration.Companion.milliseconds


@RestController
@RequestMapping("/v1/folder")
class FolderController(private val fileService: FileService) : AController() {

    @PostMapping("/create")
    fun createFolderMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String
    ): ResponseEntity<String> {
        val principal = request.getPrincipal()!!
        val path = FilePath.of(rawPath)

        val result = fileService.createFolder(
            user = principal,
            rawPath = path
        )

        if (result.rejected) return bad(result.error, "rejected")
        if (result.notFound) return notFound()
        if (result.hasError) return internal(result.error, "")

        return ok("ok")
    }


    @PostMapping("/list")
    fun listFolderItemsMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String
    ): ResponseEntity<String> {
        val principal = request.getPrincipal()!!
        val path = FilePath.of(rawPath)

        val result = let {
            val pathResult = resolvePath(path)
            if (pathResult.isNotSuccessful) return@let pathResult.cast()
            val canonicalPath = pathResult.value

            fileService.getFolderEntries(
                user = principal,
                canonicalPath = canonicalPath,
            )
        }

        if (result.rejected) return bad(result.error, "rejected")
        if (result.hasError) return bad(result.error, "")
        if (result.notFound) return notFound()
        val list = result.value

        val serialized = JsonNonNull.encodeToString(list)
        return ok(serialized)
    }

    @Unauthenticated
    @PostMapping("/file-and-folder-entries")
    fun fileOrFolderEntriesMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String,
        @RequestParam("foldersOnly") rawFoldersOnly: String,
        @RequestParam("shareToken", required = false) shareToken: String?,
    ): ResponseEntity<String> {
        val principal = request.getPrincipal()
        val path = FilePath.of(rawPath)
        val foldersOnly = rawFoldersOnly.toBooleanStrictOrNull() ?: false

        if (shareToken == null && principal == null) return unauthenticated("Unauthenticated")

        val result: Result<out Pair<AbstractFileMetadata, List<AbstractFileMetadata>?>> = if (shareToken == null) {
            fileService.getFileOrFolderEntries(
                user = principal!!,
                rawPath = path,
                foldersOnly = foldersOnly
            )
        } else {
            fileService.getSharedFileOrFolderEntries(
                rawPath = path,
                foldersOnly = foldersOnly,
                shareToken = shareToken
            )
        }

        if (result.hasError) return internal(result.error)
        if (result.notFound) return notFound()
        if (result.rejected) return bad(result.error, "no-permission")
        if (result.isNotSuccessful) return bad(result.error)

        val (
            meta: AbstractFileMetadata,
            entries: List<AbstractFileMetadata>?
        ) = result.value

        val serialized = json {
            if (shareToken == null) {
                putNonNull<FullFileMetadata>("meta", meta as FullFileMetadata)
                if (entries != null) {
                    put("entries", entries as List<FullFileMetadata>?)
                }
            } else {
                putNonNull<FileMetadata>("meta", meta as FileMetadata)
                if (entries != null) {
                    put("entries", entries as List<FileMetadata>)
                }
            }
        }

        return ok(serialized)
    }

    @PostMapping("/size")
    fun folderSizeMapping(
        request: HttpServletRequest,
        response: HttpServletResponse,
        @RequestParam("path") rawPath: String
    ): ResponseEntity<StreamingResponseBody> {
        val principal = request.getPrincipal()!!
        val path = FilePath.of(rawPath)

        val prepared = fileService.prepareFolderSize(principal, path)
        if (prepared.rejected) return streamBad(prepared.error, "rejected")
        if (prepared.notFound) return streamNotFound()
        if (prepared.hasError) return streamBad(prepared.error, "")
        val canonicalPath = prepared.value

        // Small buffer so heartbeat newlines actually leave Tomcat and fail on disconnect.
        response.bufferSize = 512

        val body = StreamingResponseBody { out ->
            try {
                runBlocking {
                    val heartbeatJob = launch(Dispatchers.IO) {
                        var primed = false
                        while (isActive) {
                            if (!primed) {
                                // Fill Tomcat's buffer so the first flush is a real socket write.
                                out.write(ByteArray(512) { '\n'.code.toByte() })
                                primed = true
                            } else {
                                out.write('\n'.code)
                            }
                            out.flush()
                            response.flushBuffer()
                            delay(250.milliseconds)
                        }
                    }
                    try {
                        val result = fileService.measurePreparedFolderSize(canonicalPath)
                        heartbeatJob.cancelAndJoin()
                        out.write(folderSizePayload(result).toByteArray(StandardCharsets.UTF_8))
                        out.flush()
                    } finally {
                        heartbeatJob.cancelAndJoin()
                    }
                }
            } catch (_: IOException) {
                // Client closed the tab or aborted the fetch.
            } catch (_: CancellationException) {
                // Walk cancelled because the heartbeat write failed.
            }
        }

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CACHE_CONTROL, "no-cache")
            .header("X-Accel-Buffering", "no")
            .body(body)
    }

    private fun folderSizePayload(result: Result<FolderSize>): String {
        if (result.isNotSuccessful) {
            val code = if (result.rejected) "rejected" else ""
            return ErrorResponse(result.error, code).serialize()
        }

        val size = result.value
        return json {
            put("fileCount", size.fileCount)
            put("folderCount", size.folderCount)
            put("totalSize", size.totalSize)
            put("failedFolderCount", size.failedFolderCount)
        }
    }

}