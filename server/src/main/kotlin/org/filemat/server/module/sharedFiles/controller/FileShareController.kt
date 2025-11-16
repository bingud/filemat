package org.filemat.server.module.sharedFiles.controller

import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.Json
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.dto.ArgonHash
import org.filemat.server.common.util.encodePathSegment
import org.filemat.server.common.util.getPrincipal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.sharedFiles.model.toPublic
import org.filemat.server.module.sharedFiles.service.FileShareService
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/file/share")
class FileShareController(
    private val fileShareService: FileShareService,
    private val passwordEncoder: PasswordEncoder
) : AController() {

    @PostMapping("/get")
    fun getFileSharesMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String,
    ): ResponseEntity<String> {
        val principal = request.getPrincipal()!!
        val path = FilePath.of(rawPath)

        val shares = fileShareService.getSharesByPath(principal, path).let {
            if (it.hasError) return internal(it.error)
            if (it.rejected) return bad(it.error)
            if (it.notFound) return bad("This file was not found.")
            it.value
        }.map { it.toPublic() }

        val serialized = Json.encodeToString(shares)
        return ok(serialized)
    }

    @PostMapping("/create")
    fun createFileShareMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String,
        @RequestParam("sharePath") rawSharePath: String,
        @RequestParam("password", required = false) rawPassword: String?,
        @RequestParam("maxAge", required = false) rawMaxAge: String?,
    ): ResponseEntity<String> {
        val principal = request.getPrincipal()!!

        val path = FilePath.of(rawPath)
        val sharePath = encodePathSegment(rawSharePath)
        val password = rawPassword?.let { ArgonHash(passwordEncoder.encode(rawPassword)) }
        val maxAge = rawMaxAge?.toLongOrNull()

        val share = fileShareService.createShare(
            principal = principal,
            rawPath = path,
            sharePath = sharePath,
            password = password,
            maxAge = maxAge
        ).let {
            if (it.notFound) return bad("This file was not found.")
            if (it.rejected) return bad(it.error)
            if (it.hasError) return internal(it.error)
            it.value
        }

        val serialized = Json.encodeToString(share)
        return ok(serialized)
    }
}