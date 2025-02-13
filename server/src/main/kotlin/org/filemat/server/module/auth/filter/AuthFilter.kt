package org.filemat.server.module.auth.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.filemat.server.module.auth.service.AuthService
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class AuthFilter(private val authService: AuthService) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val token = request.cookies.find { it.name == "filemat-auth-token" }?.value
        if (token == null) {
            response.status = 401
            return
        }

        val principal = authService.getAuthByToken(token)
        if (principal.hasError) {
            response.status = 500
            response.writer.write(principal.error)
            return
        }
        if (principal.notFound) {
            response.status = 401
            return
        }

        request.setAttribute("auth", principal.value)
        return chain.doFilter(request, response)
    }
}