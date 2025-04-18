package org.filemat.server.module.file.controller

import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.getPrincipal
import org.filemat.server.common.util.json
import org.filemat.server.module.file.model.FileMetadata
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.FileService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v1/folder")
class FolderController(private val fileService: FileService) : AController() {

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @PostMapping("/list")
    fun listFolderItemsMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String
    ): ResponseEntity<String> {
        val principal = request.getPrincipal()!!
        val path = FilePath(rawPath)

        val result = fileService.getFolderEntries(
            user = principal,
            rawPath = path,
        )
        if (result.rejected) return bad(result.error, "rejected")
        if (result.hasError) return bad(result.error, "")
        if (result.notFound) return bad("This folder does not exist.", "folder-not-found")
        val list = result.value

        val serialized = Json.encodeToString(list)
        return ok(serialized)
    }

    @PostMapping("/file-or-folder-entries")
    fun filerOrFolderEntriesMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String,
    ): ResponseEntity<String> {
        val principal = request.getPrincipal()!!
        val path = FilePath(rawPath)

        val result = fileService.getFileOrFolderEntries(
            user = principal,
            path = path
        )
        if (result.hasError) return internal(result.error, "")
        if (result.notFound) return bad("This path does not exist.", "")
        if (result.isNotSuccessful) return bad(result.error, "")

        val pair = result.value
        val entries: List<FileMetadata>? = pair.second
        val serialized = json {
            put("meta", pair.first)
            if (entries != null) {
                put("entries", entries)
            }
        }

        return ok(serialized)
    }

}