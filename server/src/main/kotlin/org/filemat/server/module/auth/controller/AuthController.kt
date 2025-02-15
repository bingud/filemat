package org.filemat.server.module.auth.controller

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.filemat.server.common.util.Validator
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.realIp
import org.filemat.server.common.util.unixNow
import org.filemat.server.module.auth.service.AuthService
import org.filemat.server.module.auth.service.AuthTokenService
import org.filemat.server.module.log.model.LogLevel
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.model.UserAction
import org.filemat.server.module.user.service.UserService
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/auth")
class AuthController(
    private val userService: UserService,
    private val passwordEncoder: PasswordEncoder,
    private val authTokenService: AuthTokenService,
    private val logService: LogService,
    private val authService: AuthService
) : AController() {

    private fun loginLog(
        level: LogLevel,
        message: String,
        description: String,
        meta: Map<String, String>,
    ) {
        logService.createLog(
            type = LogType.AUTH,
            level = level,
            createdDate = unixNow(),
            action = UserAction.LOGIN,
            description = "description",
            message = message,
            meta = meta,
        )
    }

    @PostMapping("/login")
    fun loginMapping(
        request: HttpServletRequest,
        response: HttpServletResponse,
        @RequestHeader("User-Agent") userAgent: String,
        @RequestParam("username") username: String,
        @RequestParam("password") password: String,
    ): ResponseEntity<String> {
        val ip = request.realIp()
        val meta = mapOf("ip" to ip, "user-agent" to userAgent, "target-username" to username)

        if (username.contains("@")) {
            Validator.email(username)?.let {
                loginLog(LogLevel.WARN, "Failed login - Invalid email", it, meta)
                return bad(it, "email-invalid")
            }
        } else {
            Validator.username(username)?.let {
                loginLog(LogLevel.WARN, "Failed login - Invalid username", it, meta)
                return bad(it, "username-invalid")
            }
        }
        Validator.password(password)?.let {
            loginLog(LogLevel.WARN, "Failed login - Invalid password", it, meta)
            return bad(it, "password-invalid")
        }

        val userRes = if (username.contains("@")) userService.getUserByEmail(username, UserAction.LOGIN) else userService.getUserByUsername(username, UserAction.LOGIN)
        if (userRes.notFound) return unauthenticated("Password is incorrect.", "incorrect-password")
            .also { loginLog(LogLevel.WARN, "Failed login - Invalid account", "Account does not exist.", meta) }
        if (userRes.hasError) return internal(userRes.error, "")
        val user = userRes.value

        if (user.isBanned) return bad("This account is banned.", "banned")
            .also { loginLog(LogLevel.WARN, "Failed login - User banned", "", meta) }
        if (!passwordEncoder.matches(password, user.password)) return bad("Password is incorrect.", "incorrect-password")
            .also { loginLog(LogLevel.WARN, "Failed login - Incorrect password", "", meta) }

        val tokenR = authTokenService.createToken(userId = user.userId, userAgent = userAgent, userAction = UserAction.LOGIN)
        if (tokenR.isNotSuccessful) return internal(tokenR.error, "")
        val token = tokenR.value

        val cookie = authTokenService.createCookie(token.authToken, token.maxAge)
        response.addCookie(cookie)

        ////
            SEND PRINCIPAL WITH LOGIN
        ////
        val principal = authService.getPrincipalByUserId(user.userId)
        if (principal.)

        loginLog(LogLevel.INFO, "Successful login", "", meta)

        return ok()
    }

}