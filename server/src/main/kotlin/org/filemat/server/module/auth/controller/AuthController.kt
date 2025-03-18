package org.filemat.server.module.auth.controller

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.serialization.json.*
import org.filemat.server.common.State
import org.filemat.server.common.util.*
import org.filemat.server.common.util.JsonBuilder
import org.filemat.server.common.util.controller.AController
import org.filemat.server.config.auth.Unauthenticated
import org.filemat.server.module.auth.model.Principal.Companion.getRoles
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
import kotlin.system.measureNanoTime


@RestController
@RequestMapping("/v1/auth")
class AuthController(
    private val userService: UserService,
    private val passwordEncoder: PasswordEncoder,
    private val authTokenService: AuthTokenService,
    private val logService: LogService,
    private val authService: AuthService
) : AController() {

    @Unauthenticated
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
                loginLog(LogLevel.WARN, "Failed login - Invalid email", it, meta, ip)
                return bad(it, "email-invalid")
            }
        } else {
            Validator.username(username)?.let {
                loginLog(LogLevel.WARN, "Failed login - Invalid username", it, meta, ip)
                return bad(it, "username-invalid")
            }
        }
        Validator.password(password)?.let {
            loginLog(LogLevel.WARN, "Failed login - Invalid password", it, meta, ip)
            return bad(it, "password-invalid")
        }

        val userRes = if (username.contains("@")) userService.getUserByEmail(username, UserAction.LOGIN) else userService.getUserByUsername(username, UserAction.LOGIN)
        if (userRes.notFound) return unauthenticated("Password is incorrect.", "incorrect-password")
            .also { loginLog(LogLevel.WARN, "Failed login - Invalid account", "Account does not exist.", meta, ip) }
        if (userRes.hasError) return internal(userRes.error, "")
        val user = userRes.value

        if (user.isBanned) return bad("This account is banned.", "banned")
            .also { loginLog(LogLevel.WARN, "Failed login - User banned", "", meta, ip) }
        if (!passwordEncoder.matches(password, user.password)) return bad("Password is incorrect.", "incorrect-password")
            .also { loginLog(LogLevel.WARN, "Failed login - Incorrect password", "", meta, ip) }

        val tokenR = authTokenService.createToken(userId = user.userId, userAgent = userAgent, userAction = UserAction.LOGIN)
        if (tokenR.isNotSuccessful) return internal(tokenR.error, "")
        val token = tokenR.value

        val cookie = authTokenService.createCookie(token.authToken, token.maxAge)
        response.addCookie(cookie)

        loginLog(LogLevel.INFO, "", "Successful login", meta, ip)

        return ok()
    }

    private fun loginLog(
        level: LogLevel,
        description: String,
        message: String,
        meta: Map<String, String>,
        ip: String
    ) {
        logService.createLog(
            type = LogType.AUTH,
            level = level,
            createdDate = unixNow(),
            action = UserAction.LOGIN,
            description = description,
            message = message,
            meta = meta,
            initiatorIp = ip
        )
    }

}