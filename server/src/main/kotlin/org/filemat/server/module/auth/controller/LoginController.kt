package org.filemat.server.module.auth.controller

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.filemat.server.common.util.*
import org.filemat.server.common.util.controller.AController
import org.filemat.server.config.Props
import org.filemat.server.config.auth.Unauthenticated
import org.filemat.server.module.auth.service.LoginService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Controller for handling logins
 */
@RestController
@RequestMapping("/v1/auth/login")
class LoginController(
    private val loginService: LoginService
) : AController() {

    @Unauthenticated
    @PostMapping("/initiate")
    fun login_passwordMapping(
        request: HttpServletRequest,
        response: HttpServletResponse,
        @RequestHeader("User-Agent") userAgent: String,
        @RequestParam("username") username: String,
        @RequestParam("password") password: String,
    ): ResponseEntity<String> {
        val ip = request.realIp()
        val principal = request.getPrincipal()
        val meta = mapOf("ip" to ip, "user-agent" to userAgent, "target-username" to username)
        val now = unixNow()

        return loginService.login(
            principal = principal,
            username = username,
            password = password,
            ip = ip,
            meta = meta,
            userAgent = userAgent,
            now = now,
            response = response,
        )
    }

    @Unauthenticated
    @PostMapping("/verify-totp-mfa")
    fun login_MfaMapping(
        request: HttpServletRequest,
        response: HttpServletResponse,
        @CookieValue(Props.Cookies.tempLoginToken, required = false) loginToken: String?,
        @RequestHeader("totp") totp: String,
    ): ResponseEntity<String> {
        if (loginToken == null || loginToken.length != 48) return unauthenticated("The login has expired.", "login-expired")
        Validator.totp(totp)?.let { return bad(it, "validation") }

        return loginService
    }
}