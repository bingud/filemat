package org.filemat.server.common.controller

import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader
import org.springframework.http.MediaType
import org.springframework.http.MediaTypeFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController


/**
 * Controller for static resources
 */
@RestController
class ResourceController(
    private val resourceLoader: ResourceLoader
) {

    /**
     * Serves static files (HTML, CSS, JS, images, etc.) from the
     * classpath:static/ directory.
     */
    @GetMapping("/resource") // Matches /resource/*, /resource/*/*, etc.
    fun serveStaticFiles(request: HttpServletRequest): ResponseEntity<Resource> {
        val inputPath = request.getAttribute("path") as String

        val pathPrefix = inputPath.substringBeforeLast("/")
        val lastPathSegment = inputPath.substringAfterLast("/")
        val filenameContainsDot = lastPathSegment.contains(".")

        val path = if (inputPath == "/") {
            "$inputPath/index.html"
        } else if (!filenameContainsDot || lastPathSegment.substringAfter(".").isBlank()) {
            "$pathPrefix/$lastPathSegment.html"
        } else inputPath

        val resourcePath = "classpath:static$path"

        val (resource, resourceExists) = let {
            val initialResource =  resourceLoader.getResource(resourcePath)
            val initialResourceExists = initialResource.exists()
            if (initialResourceExists) return@let initialResource to true

            val fallbackResource = resourceLoader.getResource("classpath:static/index.html")
            val fallbackResourceExists = fallbackResource.exists()
            fallbackResource to fallbackResourceExists
        }

        if (resourceExists && resource.isReadable) {
            val mediaType = MediaTypeFactory.getMediaType(resource)
            return ResponseEntity.ok()
                .contentType(mediaType.orElse(MediaType.APPLICATION_OCTET_STREAM))
                .body(resource)
        } else {
            // File not found or not readable
            return ResponseEntity.notFound().build()
        }
    }
}