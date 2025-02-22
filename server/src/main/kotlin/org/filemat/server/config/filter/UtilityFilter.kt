package org.filemat.server.config.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.filemat.server.common.State
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter


@Order(1)
@Component
class UtilityFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        // Check if application has finished starting
        if (!State.App.isInitialized) return

        return filterChain.doFilter(request, response)
    }
}