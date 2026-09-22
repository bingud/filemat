package org.filemat.server.config.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.filemat.server.common.util.getAuthToken
import org.filemat.server.config.CorsOriginRegistry
import org.filemat.server.config.auth.corsOpenPaths
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter


/**
 * A request with an auth cookie must match that token's SPA origin.
 * A `@Cors` route with no cookie allows any origin.
 */
@Order(0)
@Component
class CorsFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val origin = request.getHeader("Origin")
        val crossOrigin = origin != null && !CorsOriginRegistry.isSameOrigin(request, origin)

        if (crossOrigin) {
            val token = request.getAuthToken()
            if (!token.isNullOrBlank()) {
                if (!CorsOriginRegistry.allows(token, origin)) {
                    // A forgotten row is recoverable. A row for another site is not.
                    response.status = if (CorsOriginRegistry.isBound(token)) {
                        HttpServletResponse.SC_FORBIDDEN
                    } else {
                        HttpServletResponse.SC_UNAUTHORIZED
                    }
                    return
                }
                applyCredentialedCors(response, origin)
            } else if (isOpenCors(request)) {
                // OPTIONS has no cookie. A bound origin means the following request is logged in.
                val preflightForSession = request.method.equals("OPTIONS", ignoreCase = true)
                    && CorsOriginRegistry.allows(null, origin)
                if (preflightForSession) {
                    applyCredentialedCors(response, origin)
                } else {
                    applyAnonymousCors(response)
                }
            } else if (!CorsOriginRegistry.allows(null, origin)) {
                if (!isContentSession(request)) {
                    response.status = HttpServletResponse.SC_FORBIDDEN
                    return
                }
                // No content cookie yet. The page reads 401 and then requests a ticket.
                applyCredentialedCors(response, origin)
                if (!request.method.equals("OPTIONS", ignoreCase = true)) {
                    response.status = HttpServletResponse.SC_UNAUTHORIZED
                    return
                }
            } else {
                // Content-session redeem and its preflight: no cookie yet, origin already bound.
                applyCredentialedCors(response, origin)
            }
        }

        if (request.method.equals("OPTIONS", ignoreCase = true) && request.requestURI.startsWith("/api")) {
            response.status = HttpServletResponse.SC_NO_CONTENT
            return
        }

        filterChain.doFilter(request, response)
    }

    /** `@Cors` path. This filter runs before `/api` is stripped. */
    private fun isOpenCors(request: HttpServletRequest): Boolean {
        return servletPath(request) in corsOpenPaths
    }

    private fun isContentSession(request: HttpServletRequest): Boolean {
        return servletPath(request) == "/v1/auth/content-session"
    }

    private fun servletPath(request: HttpServletRequest): String {
        return request.requestURI.substringBefore('?').removePrefix("/api")
    }

    /** Exact origin and Allow-Credentials. `*` is invalid once credentials are allowed. */
    private fun applyCredentialedCors(response: HttpServletResponse, origin: String) {
        response.setHeader("Access-Control-Allow-Origin", origin.trim().trimEnd('/'))
        response.setHeader("Access-Control-Allow-Credentials", "true")
        applySharedCors(response)
    }

    /** Any origin, no Allow-Credentials. */
    private fun applyAnonymousCors(response: HttpServletResponse) {
        response.setHeader("Access-Control-Allow-Origin", "*")
        applySharedCors(response)
    }

    private fun applySharedCors(response: HttpServletResponse) {
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
