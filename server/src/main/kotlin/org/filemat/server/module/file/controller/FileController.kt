package org.filemat.server.module.file.controller

import jakarta.servlet.http.HttpServletRequest
import org.filemat.server.common.util.normalizePath
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

@RequestMapping("/v1/file")
class FileController {

    @PostMapping("/list")
    fun listFolderItemsMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String
    ): ResponseEntity<String> {
        val path = normalizePath(rawPath)

    }

}