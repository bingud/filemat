package org.filemat.server.module.file.controller

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.*
import org.filemat.server.common.util.controller.AController
import org.filemat.server.config.Props
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.FileService
import org.filemat.server.module.file.service.FilesystemService
import org.filemat.server.module.file.service.TusService
import org.springframework.http.*
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.BufferedInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.pathString


@RestController
@RequestMapping("/v1/file")
class FileController(
    private val fileService: FileService,
    private val tusService: TusService,
    private val filesystemService: FilesystemService,
) : AController() {

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
            println(it.source)
            if (it.notFound) return bad("This file was not found.", "")
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
            if (it.notFound) return bad("This file was not found.", "")
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
            return ok("$it")
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

    @GetMapping("/content")
    fun getFileContentStreamMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String,
    ) = streamFileContentMapping(request = request, rawPath = rawPath)

    /**
     * Returns stream of content of a file
     *
     * Optionally returns a byte range
     */
    @PostMapping("/content")
    fun streamFileContentMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String,
    ): ResponseEntity<StreamingResponseBody> {
        val principal = request.getPrincipal()!!
        val path = FilePath.of(rawPath)
        val rawRangeHeader: String? = request.getHeader("Range")

        // Resolve file path
        val (pathResult, pathContainsSymlink) = resolvePath(path)
        val canonicalPath = pathResult.let {
            if (it.notFound) return streamBad("This file was not found.", "")
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
        val inputStream = fileService.getFileContent(principal, path, existingCanonicalPath = canonicalPath, range = range).let {
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

        // Set response display type to inline (can be displayed in browser)
        val cd: ContentDisposition = ContentDisposition.inline()
            .filename(filename, StandardCharsets.UTF_8)
            .build()

        // Construct response headers
        val headers = HttpHeaders().apply {
            set(HttpHeaders.ACCEPT_RANGES, "bytes")
            set(HttpHeaders.CONTENT_DISPOSITION, cd.toString())

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
    @PostMapping("/zip-multiple-content")
    fun streamMultipleContentZipMapping(
        request: HttpServletRequest,
        @RequestParam("pathList") rawPathList: String
    ): ResponseEntity<StreamingResponseBody> {
        val principal = request.getPrincipal()!!
        val pathStrings = rawPathList.parseJsonOrNull<List<String>>()
            ?: return streamBad("List of file paths is invalid.", "validation")
        val paths = pathStrings.map { FilePath.of(it) }

        val responseBody = StreamingResponseBody { out ->
            ZipOutputStream(out).use { zip ->

                fun addToZip(fp: FilePath, base: Path) {
                    // try to stream as file
                    fileService.getFileContent(principal, fp).let { res ->
                        if (res.isSuccessful) {
                            zip.putNextEntry(ZipEntry(base.toString()))
                            BufferedInputStream(res.value).use { it.copyTo(zip) }
                            zip.closeEntry()
                            return
                        }
                    }
                    // not a file → treat as directory
                    val dir = fp.path
                    if (Files.isDirectory(dir)) {
                        Files.walk(dir).use { walk ->
                            walk.filter { Files.isRegularFile(it) }
                                .forEach { file ->
                                    // compute ZIP entry name relative to fp.path
                                    val entryName = base.resolve(dir.relativize(file)).toString()
                                    zip.putNextEntry(ZipEntry(entryName))
                                    fileService.getFileContent(principal, FilePath.of(file.toString())).value.use {
                                        BufferedInputStream(it).copyTo(zip)
                                    }
                                    zip.closeEntry()
                                }
                        }
                    }
                }

                // seed each selected path at its own top-level name
                paths.forEach { fp ->
                    val rootName = fp.path.fileName
                    addToZip(fp, rootName)
                }
            }
        }

        val filename = "${Props.appName.lowercase()}-download-${formatUnixToFilename(Instant.now())}.zip"

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .body(responseBody)
    }
}