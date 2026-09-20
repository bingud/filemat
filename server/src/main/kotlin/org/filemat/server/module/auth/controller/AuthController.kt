package org.filemat.server.module.auth.controller

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.filemat.server.common.util.*
import org.filemat.server.common.util.controller.AController
import org.filemat.server.config.CorsOriginRegistry
import org.filemat.server.config.auth.Unauthenticated
import org.filemat.server.module.auth.service.AuthService
import org.filemat.server.module.auth.service.AuthTokenService
import org.filemat.server.module.auth.service.ContentSessionService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v1/auth")
class AuthController(
    private val authTokenService: AuthTokenService,
    private val authService: AuthService,
    private val contentSessionService: ContentSessionService,
) : AController() {

    @Unauthenticated
    @PostMapping("/logout")
    fun logoutMapping(
        request: HttpServletRequest,
    ): ResponseEntity<String> {
        val token = request.getAuthToken()
        if (token == null) return ok("ok")

        authService.logoutUser(token).let {
            if (it.isNotSuccessful) return internal(it.errorOrNull ?: "Failed to logout.", "")
        }

        return ok("ok")
    }

    @PostMapping("/content-session-ticket")
    fun createContentSessionTicketMapping(
        request: HttpServletRequest,
    ): ResponseEntity<String> {
        val origin = CorsOriginRegistry.spaOriginFrom(request)
            ?: return bad("Missing request origin.")

        val rawToken = request.getAuthToken() ?: return unauthenticated("unauthenticated")
        val authToken = authTokenService.getToken(rawToken).let {
            if (it.notFound) return unauthenticated("unauthenticated")
            if (it.isNotSuccessful) return internal(it.error)
            it.value
        }

        contentSessionService.createTicket(authToken, origin).let {
            if (it.rejected) return bad(it.error)
            if (it.isNotSuccessful) return internal(it.error)
            CorsOriginRegistry.remember(origin)
            return ok(it.value)
        }
    }

    @Unauthenticated
    @PostMapping("/content-session")
    fun contentSessionMapping(
        request: HttpServletRequest,
        response: HttpServletResponse,
        @RequestParam("ticket", required = false) ticket: String?,
    ): ResponseEntity<String> {
        val origin = CorsOriginRegistry.spaOriginFrom(request)
        val secure = request.isSecure || request.getHeader("X-Forwarded-Proto")?.equals("https", ignoreCase = true) == true

        val cookieToken = request.getAuthToken()
        if (!cookieToken.isNullOrBlank()) {
            val existing = authTokenService.getToken(cookieToken)
            if (existing.isSuccessful) {
                val maxAge = contentSessionService.cookieMaxAgeSeconds(existing.value)
                if (maxAge <= 0) return unauthenticated("unauthenticated")
                response.addHeader("Set-Cookie", contentSessionService.buildSetCookieHeader(existing.value.authToken, maxAge, secure))
                return ok()
            }
        }

        if (ticket.isNullOrBlank()) return unauthenticated("unauthenticated")
        val consumed = contentSessionService.consumeTicket(ticket)
        if (consumed.rejected) return unauthenticated(consumed.error)
        if (consumed.isNotSuccessful) return internal(consumed.error)

        val ticketData = consumed.value
        if (origin == null || ticketData.origin != origin) {
            return unauthenticated("Ticket origin mismatch.")
        }

        val authToken = authTokenService.getToken(ticketData.authToken).let {
            if (it.notFound) return unauthenticated("unauthenticated")
            if (it.isNotSuccessful) return internal(it.error)
            it.value
        }
        val maxAge = contentSessionService.cookieMaxAgeSeconds(authToken)
        if (maxAge <= 0) return unauthenticated("unauthenticated")
        response.addHeader("Set-Cookie", contentSessionService.buildSetCookieHeader(authToken.authToken, maxAge, secure))
        return ok()
    }

}