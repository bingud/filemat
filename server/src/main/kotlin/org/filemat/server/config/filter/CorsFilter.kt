package org.filemat.server.config.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.filemat.server.config.CorsOriginRegistry
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter


@Order(0)
@Component
class CorsFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val origin = request.getHeader("Origin")
        val crossOrigin = origin != null && !CorsOriginRegistry.isSameOrigin(request, origin)
        val allowCors = origin != null && crossOrigin && CorsOriginRegistry.isAllowed(origin)

        if (allowCors) {
            applyCors(response, origin)
        }

        if (crossOrigin && !allowCors) {
            response.status = HttpServletResponse.SC_FORBIDDEN
            return
        }

        if (request.method.equals("OPTIONS", ignoreCase = true) && request.requestURI.startsWith("/api")) {
            response.status = HttpServletResponse.SC_NO_CONTENT
            return
        }

        filterChain.doFilter(request, response)
    }

    private fun applyCors(response: HttpServletResponse, origin: String) {
        response.setHeader("Access-Control-Allow-Origin", origin.trim().trimEnd('/'))
        response.setHeader("Access-Control-Allow-Credentials", "true")
        response.setHeader(
            "Access-Control-Allow-Methods",
            "GET, POST, HEAD, PATCH, DELETE, OPTIONS"
        )
        response.setHeader(
            "Access-Control-Allow-Headers",
            "Content-Type, Tus-Resumable, Upload-Offset, Upload-Length, Upload-Metadata, Upload-Defer-Length, Upload-Concat, X-HTTP-Method-Override"
        )
        response.setHeader(
            "Access-Control-Expose-Headers",
            "Upload-Offset, Upload-Length, Tus-Resumable, Location, actual-uploaded-filename"
        )
        response.setHeader("Access-Control-Max-Age", "86400")
        response.setHeader("Vary", "Origin")
    }
}
