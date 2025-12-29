package org.filemat.server.module.savedFile

import jakarta.servlet.http.HttpServletRequest
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.JsonNonNull
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.getPrincipal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FullFileMetadata
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/saved-file")
class SavedFileController(
    private val savedFileService: SavedFileService,
): AController() {

    @PostMapping("/get-all")
    fun getAllSavedFilesMapping(
        request: HttpServletRequest,
    ): ResponseEntity<String> {
        val user = request.getPrincipal()!!

        savedFileService.getAll(user).let { it: Result<List<FullFileMetadata>> ->
            if (it.hasError) return internal(it.error)
            if (it.rejected) return bad(it.error)
            if (it.notFound) return ok("[]")
            return ok(JsonNonNull.encodeToString(it.value))
        }
    }

    @PostMapping("/create")
    fun createSavedFileMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String
    ): ResponseEntity<String> {
        val user = request.getPrincipal()!!
        val path = FilePath.of(rawPath)

        savedFileService.addSavedFile(user, path).let {
            if (it.hasError) return internal(it.error)
            if (it.rejected) return bad(it.error)
            return ok(it.value.serialize())
        }
    }

    @PostMapping("/remove")
    fun removeSavedFileMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String
    ): ResponseEntity<String> {
        val user = request.getPrincipal()!!
        val path = FilePath.of(rawPath)

        savedFileService.removeSavedFile(path, user).let {
            if (it.hasError) return internal(it.error)
            if (it.rejected) return bad(it.error)
            return ok("ok")
        }
    }
}