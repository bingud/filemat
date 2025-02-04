package org.filemat.server.config.filter

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.filemat.server.common.State
import org.springframework.stereotype.Component


@Component
class UtilityFilter : Filter {
    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        // Check if application has finished starting
        if (!State.App.isInitialized) return

        return chain.doFilter(request, response)
    }
}