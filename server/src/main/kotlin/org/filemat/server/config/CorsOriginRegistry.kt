package org.filemat.server.config

import jakarta.servlet.http.HttpServletRequest
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * SPA origins recorded when a logged-in client mints a content-session ticket.
 *
 * Used as the CORS allowlist when the browser later calls a different host
 * that still serves this same Filemat instance.
 */
object CorsOriginRegistry {
    private val origins = ConcurrentHashMap.newKeySet<String>()

    fun remember(origin: String) {
        val clean = origin.trim().trimEnd('/')
        if (clean.isNotEmpty()) origins.add(clean)
    }

    fun isAllowed(origin: String): Boolean {
        return origins.contains(origin.trim().trimEnd('/'))
    }

    /**
     * True when [origin] is the same scheme, host, and port as this request.
     */
    fun isSameOrigin(request: HttpServletRequest, origin: String): Boolean {
        val originValue = canonicalOrigin(origin) ?: return false
        val requestValue = requestOrigin(request) ?: return false
        return originValue.equals(requestValue, ignoreCase = true)
    }

    fun spaOriginFrom(request: HttpServletRequest): String? {
        request.getHeader("Origin")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.trimEnd('/') }
        val referer = request.getHeader("Referer") ?: return null
        return try {
            val uri = URI(referer)
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            val port = uri.port
            if (port == -1) "$scheme://$host" else "$scheme://$host:$port"
        } catch (_: Exception) {
            null
        }
    }

    internal fun requestOrigin(request: HttpServletRequest): String? {
        val scheme = forwardedProto(request) ?: request.scheme ?: return null
        val hostHeader = request.getHeader("Host")?.trim()?.takeIf { it.isNotEmpty() }
        if (hostHeader != null) {
            return canonicalOrigin("$scheme://$hostHeader")
        }
        val host = request.serverName ?: return null
        return formatOrigin(scheme, host, request.serverPort)
    }

    internal fun canonicalOrigin(origin: String): String? {
        return try {
            val uri = URI(origin.trim().trimEnd('/'))
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            formatOrigin(scheme, host, uri.port)
        } catch (_: Exception) {
            null
        }
    }

    private fun forwardedProto(request: HttpServletRequest): String? {
        return request.getHeader("X-Forwarded-Proto")
            ?.substringBefore(',')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun formatOrigin(scheme: String, host: String, port: Int): String {
        val schemeNorm = scheme.lowercase()
        val hostNorm = host.lowercase()
        val defaultPort = if (schemeNorm == "https") 443 else 80
        val effectivePort = if (port == -1) defaultPort else port
        return if (effectivePort == defaultPort) {
            "$schemeNorm://$hostNorm"
        } else {
            "$schemeNorm://$hostNorm:$effectivePort"
        }
    }
}
