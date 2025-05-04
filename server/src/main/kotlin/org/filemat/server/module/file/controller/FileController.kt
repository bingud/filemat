package org.filemat.server.module.file.controller

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.getPrincipal
import org.filemat.server.common.util.parseJsonOrNull
import org.filemat.server.common.util.tika
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


@RestController
@RequestMapping("/v1/file")
class FileController(
    private val fileService: FileService,
    private val tusService: TusService,
) : AController() {

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
    fun getListFolderItemsMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String,
    ) = listFolderItemsMapping(request = request, rawPath = rawPath)

    @PostMapping("/content")
    fun listFolderItemsMapping(
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
}