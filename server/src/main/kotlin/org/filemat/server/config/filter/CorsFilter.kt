package org.filemat.server.config.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.filemat.server.common.util.getAuthToken
import org.filemat.server.config.CorsOriginRegistry
import org.filemat.server.config.auth.corsOpenPaths
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter


/**
 * A request with an auth cookie must match that token's SPA origin.
 * A `@Cors` route with no cookie allows any origin.
 */
@Order(0)
@Component
class CorsFilter(
    @Value("\${server.servlet.context-path:}") configuredContextPath: String = "",
) : OncePerRequestFilter() {

    /** Empty unless the process was started with `server.servlet.context-path`. */
    private val contextPath = normalizeContextPath(configuredContextPath)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val origin = request.getHeader("Origin")
        val crossOrigin = origin != null && !CorsOriginRegistry.isSameOrigin(request, origin)
        val options = request.method.equals("OPTIONS", ignoreCase = true)
        val path = if (crossOrigin || options) requestPath(request) else null

        if (crossOrigin) {
            val route = path!!.removePrefix("/api")
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
            } else if (route in corsOpenPaths) {
                // OPTIONS has no cookie. A bound origin means the following request is logged in.
                val preflightForSession = options && CorsOriginRegistry.allows(null, origin)
                if (preflightForSession) {
                    applyCredentialedCors(response, origin)
                } else {
                    applyAnonymousCors(response)
                }
            } else if (!CorsOriginRegistry.allows(null, origin)) {
                if (route != "/v1/auth/content-session") {
                    response.status = HttpServletResponse.SC_FORBIDDEN
                    return
                }
                // No content cookie yet. The page reads 401 and then requests a ticket.
                applyCredentialedCors(response, origin)
                if (!options) {
                    response.status = HttpServletResponse.SC_UNAUTHORIZED
                    return
                }
            } else {
                // Content-session redeem and its preflight: no cookie yet, origin already bound.
                applyCredentialedCors(response, origin)
            }
        }

        if (options && path!!.startsWith("/api")) {
            response.status = HttpServletResponse.SC_NO_CONTENT
            return
        }

        filterChain.doFilter(request, response)
    }

    /** `requestURI` has no query string. Context path is stripped only when one was configured. */
    private fun requestPath(request: HttpServletRequest): String {
        val uri = request.requestURI
        if (contextPath.isEmpty()) return uri
        if (uri.length > contextPath.length && uri.startsWith(contextPath) && uri[contextPath.length] == '/') {
            return uri.substring(contextPath.length)
        }
        return uri
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

private fun normalizeContextPath(raw: String): String {
    val value = raw.trim().trimEnd('/')
    if (value.isEmpty() || value == "/") return ""
    return if (value[0] == '/') value else "/$value"
}
