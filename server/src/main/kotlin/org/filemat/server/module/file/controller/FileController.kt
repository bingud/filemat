package org.filemat.server.module.file.controller

import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.getPrincipal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.FileService
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.OutputStream
import java.net.URLConnection


@RestController
@RequestMapping("/v1/file")
class FileController(private val fileService: FileService) : AController() {

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
        val path = FilePath(rawPath)

        val inputStream = fileService.getFileContent(principal, path).let {
            if (it.notFound) return streamBad("This file was not found.", "")
            if (it.rejected) return streamBad(it.error, "")
            if (it.isNotSuccessful) return streamInternal(it.error, "")
            BufferedInputStream(it.value)
        }

        val filename = path.path.substringAfterLast("/")

        // Mark the stream at the beginning
        inputStream.mark(512)

        // Guess the MIME type using the initial bytes
        val mimeType = URLConnection.guessContentTypeFromStream(inputStream)
            ?: MediaType.APPLICATION_OCTET_STREAM_VALUE

        // Reset to the beginning after reading
        inputStream.reset()

        // Create the response body for streaming
        val responseBody = StreamingResponseBody { outputStream ->
            inputStream.use { stream ->
                val buffer = ByteArray(8192) // 8 KB buffer for efficiency
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    outputStream.flush()
                }
            }

        }

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(mimeType))
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"$filename\"")
            .body(responseBody)
    }
}