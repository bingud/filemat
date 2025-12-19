package org.filemat.server.module.file.controller

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.*
import org.filemat.server.common.util.controller.AController
import org.filemat.server.config.Props
import org.filemat.server.config.auth.Unauthenticated
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FullFileMetadata
import org.filemat.server.module.file.service.FileService
import org.filemat.server.module.file.service.FilesystemService
import org.filemat.server.module.file.service.TusService
import org.filemat.server.module.user.model.UserAction
import org.springframework.http.*
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.pathString


@RestController
@RequestMapping("/v1/file")
class FileController(
    private val fileService: FileService,
    private val tusService: TusService,
    private val filesystemService: FilesystemService,
) : AController() {

    @Unauthenticated
    @PostMapping("/search", produces = [ "application/x-ndjson" ])
    fun searchFilesMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String,
        @RequestParam("shareToken") shareToken: String?,
        @RequestParam("text") text: String,
    ): ResponseEntity<StreamingResponseBody> {
        val user = request.getPrincipal()
        val path = FilePath.of(rawPath)

        val resolvedPath = fileService.resolvePathWithOptionalShare(path, shareToken).let {
            if (it.notFound) return streamBad("This file was not found.")
            if (it.rejected) return streamBad(it.error)
            if (it.hasError) return streamInternal(it.error)
            it.value
        }

        val resultFlow: Flow<Result<FullFileMetadata>> = fileService.searchFiles(
            user = user,
            canonicalPath = resolvedPath,
            text = text,
            userAction = UserAction.SEARCH_FILE
        )

        val body = StreamingResponseBody { out ->
            out.bufferedWriter().use { writer ->
                runBlocking {
                    // Start Heartbeat (runs concurrently)
                    val heartbeatJob = launch(Dispatchers.IO) {
                        while (isActive) { // Checks for cancellation
                            delay(8_000)
                            synchronized(writer) {
                                writer.write("\n")
                                writer.flush()
                            }
                        }
                    }

                    try {
                        // Collect the Flow (blocks runBlocking until done)
                        resultFlow.collect { result ->
                            if (result.isSuccessful) {
                                synchronized(writer) {
                                    writer.write(Json.encodeToString(result.value))
                                    writer.write("\n")
                                    writer.flush()
                                }
                            }
                        }
                    } finally {
                        // Stop heartbeat when collection finishes
                        heartbeatJob.cancelAndJoin()
                    }
                }
            }
        }

        return streamOk(body)
    }

    @PostMapping("/edit")
    fun editFileMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String,
        @RequestParam("content") newContent: String,
    ): ResponseEntity<String> {
        val user = request.getPrincipal()!!
        val path = FilePath.of(rawPath)

        fileService.editFile(user = user, rawPath = path, newContent = newContent)
            .let {
                if (it.hasError) return internal(it.error)
                if (it.notFound) return bad("This file does not exist.")
                if (it.rejected) return bad(it.error)

                val result = it.value
                val json = json {
                    put("modifiedDate", result.modifiedDate)
                    put("size", result.size)
                }
                return ok(json)
            }
    }

    @PostMapping("/all-shared")
    fun getAllSharedFilesMapping(
        request: HttpServletRequest,
        @RequestParam("getAll") rawGetAll: String
    ): ResponseEntity<String> {
        val principal = request.getPrincipal()!!
        val getAll = rawGetAll.toBooleanStrictOrNull() ?: return bad("Parameter 'getAll' is invalid (should be true or false).")

        val result = fileService.getSharedFileList(principal, getAll, UserAction.GET_FILE_SHARES).let {
            if (it.hasError) return internal(it.error, "")
            if (it.rejected) return bad(it.error)
            it.value
        }
        val serialized = Json.encodeToString(result)

        return ok(serialized)
    }

    @PostMapping("/all-permitted")
    fun getAllPermittedFilesMapping(
        request: HttpServletRequest,
    ): ResponseEntity<String> {
        val principal = request.getPrincipal()!!

        val result = fileService.getPermittedFileList(principal).let {
            if (it.hasError) return internal(it.error, "")
            it.value
        }
        val serialized = Json.encodeToString(result)

        return ok(serialized)
    }

    @Unauthenticated
    @PostMapping("/last-modified-date")
    fun getFileLastModifiedDateMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String,
        @RequestParam("shareToken", required = false) shareToken: String?
    ): ResponseEntity<String> {
        val principal = request.getPrincipal()
        val path = FilePath.of(rawPath)

        val resolvedPath = fileService.resolvePathWithOptionalShare(path, shareToken).let {
            if (it.notFound) return bad("This file was not found.")
            if (it.rejected) return bad(it.error)
            if (it.hasError) return internal(it.error)
            it.value
        }

        fileService.getMetadata(
            principal,
            resolvedPath,
            isPathCanonical = shareToken != null
        ).let {
            if (it.notFound) return notFound()
            if (it.hasError) return internal(it.error, "")
            if (it.isNotSuccessful) return bad(it.error, "")
            return ok(it.value.modifiedDate.toString())
        }
    }

    @PostMapping("/move")
    fun moveFileMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String,
        @RequestParam("newPath") rawNewPath: String,
    ): ResponseEntity<String> {
        val user = request.getPrincipal()!!
        val path = FilePath.of(rawPath)
        val newPath = FilePath.of(rawNewPath)

        fileService.moveFile(user = user, rawPath = path, rawNewPath = newPath).let {
            if (it.notFound) return notFound()
            if (it.rejected) return bad(it.error, "")
            if (it.hasError) return internal(it.error, "")
            return ok("ok")
        }
    }

    @PostMapping("/move-multiple")
    fun moveMultipleFileMapping(
        request: HttpServletRequest,
        @RequestParam("newParent") rawNewParent: String,
        @RequestParam("paths") rawPathList: String,
    ): ResponseEntity<String> {
        val user = request.getPrincipal()!!
        val paths = Json.decodeFromStringOrNull<List<String>>(rawPathList)?.map { FilePath.of(it) }
            ?: return bad("Invalid list of file paths to move.", "validation")
        val newParentPath = FilePath.of(rawNewParent)

        fileService.moveMultipleFiles(user = user, paths, newParentPath).let { it: Result<List<FilePath>> ->
            if (it.notFound) return notFound()
            if (it.rejected) return bad(it.error, "")
            if (it.hasError) return internal(it.error, "")

            val movedFiles = it.value
            val serialized = Json.encodeToString(movedFiles.map { it.originalInputPath.pathString })
            return ok(serialized)
        }
    }

    @PostMapping("/delete-list")
    fun deleteFileMapping(
        request: HttpServletRequest,
        @RequestParam("pathList") rawList: String
    ): ResponseEntity<String> {
        val user = request.getPrincipal()!!
        val stringList = rawList.parseJsonOrNull<List<String>>()
            ?: return bad("List of file paths is invalid.", "validation")
        val pathList = stringList.map { FilePath.of(it) }

        fileService.deleteFiles(user, pathList).let {
            val serialized = Json.encodeToString(it.map { it.pathString })
            return ok(serialized)
        }
    }

    @RequestMapping(value = ["/upload", "/upload/{uploadId}"], method = [RequestMethod.OPTIONS, RequestMethod.POST, RequestMethod.HEAD, RequestMethod.PATCH, RequestMethod.DELETE])
    fun handleTusRequest(
        request: HttpServletRequest,
        response: HttpServletResponse,
        @PathVariable("uploadId", required = false) uploadId: String?
    ) {
        tusService.handleTusUpload(
            request = request,
            response = response,
        )
    }

    /**
     * Returns stream of content of a file
     *
     * Optionally returns a byte range
     */
    @Unauthenticated
    @RequestMapping("/content")
    fun streamFileContentMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String,
        @RequestParam("shareToken", required = false) shareToken: String?,
    ): ResponseEntity<StreamingResponseBody> {
        val principal = request.getPrincipal()
        val path = FilePath.of(rawPath)
        val rawRangeHeader: String? = request.getHeader("Range")

        val canonicalPath = fileService.resolvePathWithOptionalShare(path, shareToken = shareToken).let {
            if (it.notFound) return streamNotFound()
            if (it.isNotSuccessful) {
                return streamInternal(it.error, "")
            }
            it.value
        }
        val fileSize = filesystemService.getSize(canonicalPath).let {
            if (it.notFound) return streamBad("This file was not found.", "")
            if (it.isNotSuccessful) return streamInternal(it.error, "")
            it.value
        }

        val range = if (rawRangeHeader != null) {
            parseRangeHeaderToLongRange(rawRangeHeader, length = fileSize)
                ?: return streamResponse("Invalid byte range.", HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value())
        } else null

        // Get the file content
        val inputStream = fileService.getFileContent(
            principal,
            path,
            existingCanonicalPath = canonicalPath,
            range = range,
            ignorePermissions = shareToken != null
        ).let {
            if (it.notFound) return streamBad("This file was not found.", "")
            if (it.rejected) return streamBad(it.error, "")
            if (it.isNotSuccessful) return streamInternal(it.error, "")
            BufferedInputStream(it.value)
        }

        val filename = path.pathString.substringAfterLast("/")

        // Get mimetype from either byte stream or filename extension
        val mimeType = if (range == null) {
            // Mark the stream at the beginning
            inputStream.mark(4096)

            // Guess the MIME type using Apache Tika
            val type = tika.detect(inputStream)
                ?: MediaType.APPLICATION_OCTET_STREAM_VALUE

            // Reset to the beginning after reading
            inputStream.reset()
            type
        } else tika.detect(path.pathString)

        // Add byte stream to response
        val responseBody = StreamingResponseBody { outputStream ->
            inputStream.use { stream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    outputStream.flush()
                }
            }
        }

        val encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8)
            .replace("+", "%20")

        // Construct response headers
        val headers = HttpHeaders().apply {
            set(HttpHeaders.ACCEPT_RANGES, "bytes")
            set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''$encodedFilename")

            if (range != null) {
                set(HttpHeaders.CONTENT_RANGE, "bytes ${range.first}-${range.last}/${fileSize}")
                set(HttpHeaders.CONTENT_LENGTH, ((range.last - range.first) + 1).toString())
            } else {
                set(HttpHeaders.CONTENT_LENGTH, fileSize.toString())
            }
        }

        val response = if (range == null) ResponseEntity.ok() else ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
        return response
            .headers(headers)
            .contentType(MediaType.parseMediaType(mimeType))
            .body(responseBody)
    }

    /**
     * Returns a stream of ZIP file of multiple selected files
     */
    @Unauthenticated
    @PostMapping("/zip-multiple-content")
    fun streamMultipleContentZipMapping(
        request: HttpServletRequest,
        @RequestParam("pathList") rawPathList: String,
        @RequestParam("shareToken", required = false) shareToken: String?,
    ): ResponseEntity<StreamingResponseBody> {
        val principal = request.getPrincipal()
        val pathStrings = rawPathList.parseJsonOrNull<List<String>>()
            ?: return streamBad("List of file paths is invalid.", "validation")
        val paths = pathStrings.map { FilePath.of(it) }

        val responseBody = StreamingResponseBody { out: OutputStream ->
            ZipOutputStream(out).use { zip: ZipOutputStream ->
                paths.forEach { path ->
                    val zipRootName: Path? = path.path.fileName
                    addToZip(zip, path, zipRootName, principal, shareToken)
                }
            }
        }

        val filename = "${Props.appName.lowercase()}-download-${formatUnixToFilename(Instant.now())}.zip"
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .body(responseBody)
    }

    private fun addToZip(
        zip: ZipOutputStream,
        rawPath: FilePath,
        existingBaseZipPath: Path?,
        principal: Principal?,
        shareToken: String?
    ) {
        val isShared = shareToken != null
        val (
            canonicalPathResult,
            pathContainsSymlink
        ) = fileService.resolvePathWithOptionalShare(
            path = rawPath,
            shareToken = shareToken,
            withPathContainsSymlink = true
        )

        val canonicalPath = canonicalPathResult.let {
            if (it.isNotSuccessful) return
            it.value
        }

        val baseZipPath: Path? = existingBaseZipPath ?: canonicalPath.path.fileName

        // 1. Try to process as a single File
        fileService.getFileContent(
            user = principal,
            rawPath = if (isShared) canonicalPath else rawPath,
            existingCanonicalPath = canonicalPath,
            existingPathContainsSymlink = pathContainsSymlink,
            ignorePermissions = isShared
        ).let { result ->
            if (result.isSuccessful) {
                val entryName = baseZipPath.toString()
                zip.putNextEntry(ZipEntry(entryName))
                BufferedInputStream(result.value).use { stream -> stream.copyTo(zip) }
                zip.closeEntry()
                return
            }
        }

        // 2. Treat as Directory if not a file
        if (Files.isDirectory(canonicalPath.path)) {
            Files.walk(canonicalPath.path).use { walk ->
                val files = walk.filter { !Files.isSymbolicLink(it) }

                files.forEach { realPath ->
                    val relativeRealPath = canonicalPath.path.relativize(realPath)
                    val filePath = FilePath.ofAlreadyNormalized(realPath)

                    val entryName = baseZipPath?.resolve(relativeRealPath)?.toString() ?: relativeRealPath.toString()

                    if (realPath.isRegularFile()) {
                        zip.putNextEntry(ZipEntry(entryName))

                        fileService.getFileContent(
                            user = principal,
                            rawPath = filePath,
                            existingCanonicalPath = filePath,
                            ignorePermissions = isShared
                        ).let { result ->
                            if (result.isSuccessful) {
                                result.value.use { inputStream ->
                                    BufferedInputStream(inputStream).copyTo(zip)
                                }
                            }
                        }
                    } else if (realPath.isDirectory()) {
                        if (isShared || fileService.isAllowedToAccessFile(principal, filePath).isSuccessful) {
                            val dirEntryName = entryName + "/"
                            zip.putNextEntry(ZipEntry(dirEntryName))
                        }
                    }

                    zip.closeEntry()
                }
            }
        }
    }
}























