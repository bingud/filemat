package org.filemat.server.module.file.controller

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.formatUnixToFilename
import org.filemat.server.common.util.getPrincipal
import org.filemat.server.common.util.parseJsonOrNull
import org.filemat.server.common.util.tika
import org.filemat.server.config.Props
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.FileService
import org.filemat.server.module.file.service.TusService
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.BufferedInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


@RestController
@RequestMapping("/v1/file")
class FileController(
    private val fileService: FileService,
    private val tusService: TusService,
) : AController() {

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @PostMapping("/move")
    fun moveFileMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String,
        @RequestParam("newPath") rawNewPath: String,
    ): ResponseEntity<String> {
        TODO()
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
     */
    @PostMapping("/content")
    fun streamFileContentMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String
    ): ResponseEntity<StreamingResponseBody> {
        val principal = request.getPrincipal()!!
        val path = FilePath.of(rawPath)

        val inputStream = fileService.getFileContent(principal, path).let {
            if (it.notFound) return streamBad("This file was not found.", "")
            if (it.rejected) return streamBad(it.error, "")
            if (it.isNotSuccessful) return streamInternal(it.error, "")
            BufferedInputStream(it.value)
        }

        val filename = path.pathString.substringAfterLast("/")

        // Mark the stream at the beginning
        inputStream.mark(512)

        // Guess the MIME type using Apache Tika
        val mimeType = tika.detect(inputStream)
            ?: MediaType.APPLICATION_OCTET_STREAM_VALUE

        // Reset to the beginning after reading
        inputStream.reset()

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

        val cd: ContentDisposition = ContentDisposition.inline()
            .filename(filename, StandardCharsets.UTF_8)
            .build()

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(mimeType))
            .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
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