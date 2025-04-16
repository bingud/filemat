package org.filemat.server.common.util.classes

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper


/**
 * Overrides the path-related properties of an HTTP request.
 */
class RequestPathOverrideWrapper(
    request: HttpServletRequest,
    private val newPath: String
) : HttpServletRequestWrapper(request) {

    override fun getRequestURI(): String = newPath

    override fun getServletPath(): String = newPath

    override fun getPathInfo(): String? = null

    override fun getContextPath(): String = ""

    override fun getRequestURL(): StringBuffer {
        val url = "${request.scheme}://${request.serverName}:${request.serverPort}$newPath"
        return StringBuffer(url)
    }

    override fun getAttribute(name: String): Any? {
        // Defensive: override attributes related to path if needed
        return if (name == "javax.servlet.include.request_uri") newPath else super.getAttribute(name)
    }
}
