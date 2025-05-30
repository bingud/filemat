package org.filemat.server.module.auth.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.filemat.server.common.State
import org.filemat.server.common.util.getPrincipal
import org.filemat.server.common.util.measureMillis
import org.filemat.server.config.Props
import org.filemat.server.config.auth.endpointAuthMap
import org.filemat.server.module.auth.model.Principal.Companion.getPermissions
import org.filemat.server.module.permission.model.SystemPermission
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import kotlin.system.measureTimeMillis

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
        if (endpoint.authenticated) let {
            val auth = request.getPrincipal()
            if (auth == null) {
                response.status = 401
                response.writer.write("unauthenticated")
                return
            }

            if (endpoint.requiredPermissions.isEmpty()) return@let

            val permissions = auth.getPermissions()
            if (permissions.contains(SystemPermission.SUPER_ADMIN)) return@let

            // Check permissions
            endpoint.requiredPermissions.forEach {
                if (!permissions.contains(it)) {
                    response.status = 403
                    response.writer.write("unauthorized")
                    return
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