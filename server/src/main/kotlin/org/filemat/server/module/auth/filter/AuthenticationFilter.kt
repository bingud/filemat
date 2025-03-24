package org.filemat.server.module.auth.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.filemat.server.module.auth.service.AuthService
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Order(2)
@Component
class AuthenticationFilter(private val authService: AuthService) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val token = request.cookies?.find { it.name == "filemat-auth-token" }?.value

        if (token == null) {
            request.setAttribute("auth", null)
            return chain.doFilter(request, response)
        }

        val principal = authService.getPrincipalByToken(token, true)
        if (principal.hasError) {
            response.status = 500
            response.writer.write(principal.error)
            return
        }

        request.setAttribute("auth", principal.valueOrNull)
        return chain.doFilter(request, response)
    }
}