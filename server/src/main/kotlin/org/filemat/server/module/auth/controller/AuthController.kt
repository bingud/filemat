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

    @PostMapping("/state")
    fun authStateMapping(
        request: HttpServletRequest,
        @RequestParam("principal", required = false) rawPrincipal: String?,
        @RequestParam("roles", required = false) rawRoles: String?,
    ): ResponseEntity<String> {
            val principal = request.getAuth()!!

            val getPrincipal = rawPrincipal?.toBooleanStrictOrNull()
            val getRoles = rawRoles?.toBooleanStrictOrNull()
            if (getPrincipal == null && getRoles == null) return bad("Auth state request did not request anything.", "")

            val builder = JsonBuilder()

            // Return user principal
            if (getPrincipal == true) {
                val principalBuilder = JsonBuilder()

                principalBuilder.put("value", Json.encodeToJsonElement(principal))

                builder.put("principal", principalBuilder.build())
            }
            // Return all available roles
            if (getRoles == true) {
                val roleBuilder = JsonBuilder()

                val roles = State.Auth.roleMap.values.toList()
                roleBuilder.put("value", Json.encodeToJsonElement(roles))

                builder.put("roles", roleBuilder.build())
            }

            val serialized = builder.toString()
            return ok(serialized)
    }

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

//        val principal = authService.getPrincipalByUserId(user.userId)
//        if (principal.)

        loginLog(LogLevel.INFO, "Successful login", "", meta)

        return ok()
    }

}