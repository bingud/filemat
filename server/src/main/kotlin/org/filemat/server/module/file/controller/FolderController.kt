package org.filemat.server.module.file.controller

import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.filemat.server.common.model.cast
import org.filemat.server.common.util.resolvePath
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.getPrincipal
import org.filemat.server.common.util.json
import org.filemat.server.module.file.model.FileMetadata
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FullFileMetadata
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
            val (pathResult, pathHasSymlink) = resolvePath(path)
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

        val serialized = Json.encodeToString(list)
        return ok(serialized)
    }

    @PostMapping("/file-or-folder-entries")
    fun fileOrFolderEntriesMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String,
        @RequestParam("foldersOnly") rawFoldersOnly: String,
    ): ResponseEntity<String> {
        val principal = request.getPrincipal()!!
        val path = FilePath.of(rawPath)
        val foldersOnly = rawFoldersOnly.toBooleanStrictOrNull() ?: false

        val result = fileService.getFileOrFolderEntries(
            user = principal,
            rawPath = path,
            foldersOnly = foldersOnly
        )
        if (result.hasError) return internal(result.error, "")
        if (result.notFound) return notFound()
        if (result.isNotSuccessful) return bad(result.error, "")

        val pair = result.value
        val entries: List<FullFileMetadata>? = pair.second
        val serialized = json {
            put("meta", pair.first)
            if (entries != null) {
                put("entries", entries)
            }
        }

        return ok(serialized)
    }

}