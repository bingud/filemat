package org.filemat.server.module.auth.service

import com.atlassian.onetime.model.TOTPSecret
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.f4b6a3.ulid.Ulid
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletResponse
import kotlinx.serialization.json.Json
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.handle
import org.filemat.server.common.util.*
import org.filemat.server.common.util.controller.AController
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.log.model.LogLevel
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.model.User
import org.filemat.server.module.user.model.UserAction
import org.filemat.server.module.user.service.UserService
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class LoginService(
    private val userService: UserService,
    private val passwordEncoder: PasswordEncoder,
    private val authTokenService: AuthTokenService,
    private val logService: LogService,
    private val authService: AuthService,
    private val mfaService: MfaService
): AController() {

    private val tempTokenExpirationSeconds = 600

    // Cache for temp login tokens
    val loginTokenCache = Caffeine.newBuilder()
        .expireAfterWrite(tempTokenExpirationSeconds.toLong(), TimeUnit.SECONDS)
        .maximumSize(100_000)
        .build<String, Ulid>()

    /**
     * Creates a response to a login request
     */
    fun login(
        existingPrincipal: Principal?,
        username: String?,
        password: String?,
        ip: String,
        meta: Map<String, String>,
        userAgent: String,
        now: Long,
        response: HttpServletResponse,
    ): ResponseEntity<String> {
        // Rate limit
        val rateLimit = if (existingPrincipal != null) {
            RateLimiter.consume(RateLimitId.LOGIN_AUTHED, existingPrincipal.userId.toString())
        } else RateLimiter.consume(RateLimitId.LOGIN, ip)

        if (!rateLimit.isAllowed) return rateLimited(rateLimit.millisUntilRefill)

        // Validate inputs
        verifyLoginInputs(
            username = username,
            password = password,
            meta = meta,
            ip = ip
        )?.let { return it }

        // Check existing user account
        val user = let {
            val res: Result<User> = if (username!!.contains("@")) {
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
        if (!passwordEncoder.matches(password, user.password)) return unauthenticated("Password is incorrect.", "incorrect-password")
            .also { loginLog(LogLevel.WARN, "Failed login - Incorrect password", "", meta, ip) }
        if (user.isBanned) return bad("This account is banned.", "banned")
            .also { loginLog(LogLevel.WARN, "Failed login - User banned", "", meta, ip) }

        // Check if TOTP MFA is enforced
        if (user.mfaTotpRequired && user.mfaTotpStatus == false) {
            val principal = authService.createPrincipalFromUser(user, cacheInMemory = true)
                .handle {
                    if (it.hasError) return internal(it.error)
                }

            val mfa = mfaService.enable_generateSecret(principal)
            val serialized = Json.encodeToString(mfa)
            return bad(serialized, "mfa-enforced")
        }

        // Check 2FA
        if (user.mfaTotpStatus) {
            val loginToken = StringUtils.randomString(128)
            loginTokenCache.put(loginToken, user.userId)

            val cookie = createLoginTokenCookie(loginToken)
            response.addCookie(cookie)

            return ok("mfa-totp")
        }

        // Create auth token
        val token = authTokenService.createToken(userId = user.userId, userAgent = userAgent, userAction = UserAction.LOGIN).let { result ->
            if (result.isNotSuccessful) return internal(result.error, "")
            return@let result.value
        }
        
        val cookie = authTokenService.createCookie(token.authToken, token.maxAge)
        response.addCookie(cookie)

        loginLog(LogLevel.INFO, "", "Successful login", meta, ip)
        userService.setLastLoginDate(user.userId, now)

        return ok("ok")
    }

    private fun verifyLoginInputs(username: String?, password: String?, meta: Map<String, String>, ip: String): ResponseEntity<String>? {
        if (username == null) return bad("The username is blank.", "username-invalid")
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

        return null
    }

    /**
     * Creates a response for login 2FA verification
     */
    fun verifyTotpMfa(
        loginToken: String,
        totp: String,
        userAgent: String,
        response: HttpServletResponse
    ): ResponseEntity<String> {
        val userId = loginTokenCache.getIfPresent(loginToken) ?: return unauthenticated("Login expired.", "login-expired")

        // Get user
        val user = userService.getUserByUserId(userId, UserAction.VERIFY_LOGIN_TOTP_MFA).let { result ->
            if (result.hasError) return internal(result.error)
            if (result.notFound) return bad("Login expired.", "login-expired")
            result.value
        }

        // Get MFA credentials
        if (!user.mfaTotpStatus) return bad("This account does not have 2FA enabled.")
        val totpSecretString = user.mfaTotpSecret ?: return internal("Failed to verify 2FA.")

        val totpSecret = TOTPSecret.fromBase32EncodedString(totpSecretString)

        // Verify 2FA code
        val isValid = TotpUtil.verify(totpSecret, totp)
        if (!isValid) {
            loginLog(LogLevel.WARN, "Failed login - Incorrect 2FA", "", mapOf("target-userId" to user.userId.toString(), "target-username" to user.username), "unknown")
            return bad("2FA code is incorrect.", "invalid-totp")
        }

        // Create auth token
        val token = authTokenService.createToken(userId = user.userId, userAgent = userAgent, userAction = UserAction.LOGIN).let { result ->
            if (result.isNotSuccessful) return internal(result.error, "")
            return@let result.value
        }

        val cookie = authTokenService.createCookie(token.authToken, token.maxAge)
        response.addCookie(cookie)

        loginTokenCache.invalidate(loginToken)

        return ok("ok")
    }

    private fun createLoginTokenCookie(token: String): Cookie {
        return Cookie(Props.Cookies.tempLoginToken, token).apply {
            secure = true
            isHttpOnly = true
            path = "/"
            maxAge = tempTokenExpirationSeconds
            setAttribute("SameSite", "Lax")
        }
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