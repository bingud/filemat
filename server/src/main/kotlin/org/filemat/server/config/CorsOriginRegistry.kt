package org.filemat.server.config

import com.github.f4b6a3.ulid.Ulid
import jakarta.servlet.http.HttpServletRequest
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * SPA origin bound to the auth token that minted a content session.
 *
 * Credentialed CORS is allowed only when the request cookie's token was bound to
 * that origin. A request with no cookie is allowed when some live session was
 * bound to the origin, so ticket redeem and preflight can run.
 */
object CorsOriginRegistry {
    private data class Binding(val userId: Ulid, val origin: String)

    private val bindings = ConcurrentHashMap<String, Binding>()

    fun bind(authToken: String, userId: Ulid, origin: String) {
        val canonical = canonicalOrigin(origin) ?: return
        if (authToken.isBlank()) return
        bindings[authToken] = Binding(userId, canonical)
    }

    fun unbind(authToken: String) {
        if (authToken.isNotBlank()) bindings.remove(authToken)
    }

    fun unbindUser(userId: Ulid, excludedToken: String? = null) {
        val tokens = bindings.filter { (token, binding) ->
            binding.userId == userId && token != excludedToken
        }.keys
        tokens.forEach { bindings.remove(it) }
    }

    fun isAllowed(origin: String): Boolean {
        return allows(null, origin)
    }

    /**
     * [authToken] is the content-session cookie. When it is present, only that
     * token's origin matches. When it is absent, any bound session for [origin] matches.
     */
    fun allows(authToken: String?, origin: String): Boolean {
        val canonical = canonicalOrigin(origin) ?: return false
        if (!authToken.isNullOrBlank()) {
            return bindings[authToken]?.origin == canonical
        }
        return bindings.values.any { it.origin == canonical }
    }

    /**
     * True when [origin] is the same scheme, host, and port as this request.
     */
    fun isSameOrigin(request: HttpServletRequest, origin: String): Boolean {
        val originValue = canonicalOrigin(origin) ?: return false
        val requestValue = requestOrigin(request) ?: return false
        return originValue.equals(requestValue, ignoreCase = true)
    }

    /**
     * Origin used to bind content-session tickets and enroll the SPA in the CORS allowlist.
     *
     * Only a well-formed Origin header that matches this request, or an origin already
     * trusted as this instance's SPA, is returned. Referer is never used.
     */
    fun spaOriginFrom(request: HttpServletRequest): String? {
        val raw = request.getHeader("Origin")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val origin = canonicalOrigin(raw) ?: return null
        if (!isSameOrigin(request, origin) && !isAllowed(origin)) return null
        return origin
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
