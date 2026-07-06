package org.filemat.server.module.file.controller

import jakarta.servlet.http.HttpServletRequest
import org.filemat.server.common.util.JsonNonNull
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.getPrincipal
import org.filemat.server.common.util.parseJsonOrNull
import org.filemat.server.module.file.model.FolderUploadManifest
import org.filemat.server.module.file.model.FolderUploadSessionRequest
import org.filemat.server.module.file.service.FolderUploadService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/folder/upload")
class FolderUploadController(
    private val folderUploadService: FolderUploadService,
) : AController() {

    @PostMapping("/preflight")
    fun preflightMapping(
        request: HttpServletRequest,
        @RequestParam("manifest") rawManifest: String,
    ): ResponseEntity<String> {
        val principal = request.getPrincipal()!!
        val manifest = rawManifest.parseJsonOrNull<FolderUploadManifest>()
            ?: return bad("Folder upload manifest is invalid.", "validation")

        val result = folderUploadService.preflight(principal, manifest)
        if (result.rejected) return bad(result.error, "rejected")
        if (result.notFound) return notFound()
        if (result.hasError) return internal(result.error)

        return ok(JsonNonNull.encodeToString(result.value))
    }

    @PostMapping("/session")
    fun createSessionMapping(
        request: HttpServletRequest,
        @RequestParam("request") rawSessionRequest: String,
    ): ResponseEntity<String> {
        val principal = request.getPrincipal()!!
        val sessionRequest = rawSessionRequest.parseJsonOrNull<FolderUploadSessionRequest>()
            ?: return bad("Folder upload session request is invalid.", "validation")

        val result = folderUploadService.createSession(principal, sessionRequest)
        if (result.rejected) return bad(result.error, "rejected")
        if (result.notFound) return notFound()
        if (result.hasError) return internal(result.error)

        return ok(JsonNonNull.encodeToString(result.value))
    }
}
