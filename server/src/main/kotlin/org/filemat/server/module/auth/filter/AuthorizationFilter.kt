package org.filemat.server.module.auth.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.filemat.server.common.State
import org.filemat.server.common.util.getAuth
import org.filemat.server.config.Props
import org.filemat.server.config.auth.endpointAuthMap
import org.filemat.server.module.auth.model.Principal.Companion.getPermissions
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Order(3)
@Component
class AuthorizationFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val path = request.servletPath
        val endpoint = endpointAuthMap[path]

        if (endpoint == null) {
            return filterChain.doFilter(request, response)
        }

        // Check if user has authorization for secured endpoint
        if (endpoint.authenticated) {
            val auth = request.getAuth()
            if (auth == null) {
                response.status = 401
                response.writer.write("unauthenticated")
                return
            }

            if (endpoint.requiredPermissions.isNotEmpty()) {
                val permissions = auth.getPermissions()
                endpoint.requiredPermissions.forEach {
                    if (!permissions.contains(it)) {
                        response.status = 403
                        response.writer.write("unauthorized")
                        return
                    }
                }
            }
        }

        // Check if endpoint is available before app is set up
        if (!State.App.isSetup && !endpoint.isBeforeSetup) {
            response.status = 400
            response.writer.write("${Props.appName} is not set up yet.")
            return
        }

        filterChain.doFilter(request, response)
    }
}