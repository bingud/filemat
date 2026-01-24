package org.filemat.server.module.file.controller

import jakarta.servlet.http.HttpServletRequest
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.util.JsonNonNull
import org.filemat.server.common.util.resolvePath
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.getPrincipal
import org.filemat.server.common.util.json
import org.filemat.server.config.auth.Unauthenticated
import org.filemat.server.module.file.model.AbstractFileMetadata
import org.filemat.server.module.file.model.FileMetadata
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FullFileMetadata
import org.filemat.server.module.file.service.file.FileService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController


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

}