package org.filemat.server.module.auth.controller

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.apache.coyote.Response
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.*
import org.filemat.server.common.util.controller.AController
import org.filemat.server.config.auth.Unauthenticated
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.auth.service.AuthService
import org.filemat.server.module.auth.service.AuthTokenService
import org.filemat.server.module.log.model.LogLevel
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.model.User
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
        val principal = request.getPrincipal()
        val meta = mapOf("ip" to ip, "user-agent" to userAgent, "target-username" to username)
        val now = unixNow()

        // Rate limit
        val rateLimit = if (principal != null) {
            RateLimiter.consume(RateLimitId.LOGIN_AUTHED, principal.userId.toString())
        } else RateLimiter.consume(RateLimitId.LOGIN, ip)

        if (!rateLimit.isAllowed) return rateLimited(rateLimit.millisUntilRefill)

        // Validate inputs
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

        // Check existing user account
        val user = let {
            val res: Result<User> = if (username.contains("@")) {
                userService.getUserByEmail(username, UserAction.LOGIN)
            } else {
                userService.getUserByUsername(username, UserAction.LOGIN)
            }

            if (res.notFound) {
                loginLog(LogLevel.WARN, "Failed login - Invalid account", "Account does not exist.", meta, ip)
                return unauthenticated("Password is incorrect.", "incorrect-password")
            }
            if (res.isNotSuccessful) return internal(res.error, "")
            return@let res.value
        }

        // Verify the login
        if (user.isBanned) return bad("This account is banned.", "banned")
            .also { loginLog(LogLevel.WARN, "Failed login - User banned", "", meta, ip) }
        if (!passwordEncoder.matches(password, user.password)) return bad("Password is incorrect.", "incorrect-password")
            .also { loginLog(LogLevel.WARN, "Failed login - Incorrect password", "", meta, ip) }

        // Create auth token
        val token = authTokenService.createToken(userId = user.userId, userAgent = userAgent, userAction = UserAction.LOGIN).let { result ->
            if (result.isNotSuccessful) return internal(result.error, "")
            return@let result.value
        }

        val cookie = authTokenService.createCookie(token.authToken, token.maxAge)
        response.addCookie(cookie)

        userService.setLastLoginDate(user.userId, now)
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