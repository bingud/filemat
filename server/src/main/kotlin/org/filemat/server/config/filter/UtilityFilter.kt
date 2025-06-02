package org.filemat.server.config.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.filemat.server.common.State
import org.filemat.server.common.controller.ResourceController
import org.filemat.server.common.util.measureMillis
import org.filemat.server.common.util.print
import org.filemat.server.common.util.respond
import org.springframework.core.annotation.Order
import org.springframework.core.io.Resource
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Request filter for random utilities
 */
@Order(1)
@Component
class UtilityFilter(
    private val resourceController: ResourceController,
) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        // Check if application has finished starting
        if (!State.App.isInitialized) return


        if (false == request.requestURI.startsWith("/api")) {
            request.setAttribute("path", request.servletPath)

            val result: ResponseEntity<Resource> = resourceController.serveStaticFiles(request)
            response.status = result.statusCode.value()
            result.headers.forEach { (name, values) ->
                values.forEach { value -> response.addHeader(name, value) }
            }
            result.body?.inputStream?.copyTo(response.outputStream)

            return
        }

        val newServletPath = request.servletPath.removePrefix("/api")
        val wrappedRequest = object : HttpServletRequestWrapper(request) {
            override fun getServletPath(): String = newServletPath
            override fun getRequestURI(): String = request.requestURI.removePrefix("/api")
        }

        filterChain.doFilter(wrappedRequest, response)
    }
}