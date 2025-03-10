package org.filemat.server.common.controller

import com.github.f4b6a3.ulid.UlidCreator
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.serialization.json.Json
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.*
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.classes.Locker
import org.filemat.server.config.Props
import org.filemat.server.config.auth.BeforeSetup
import org.filemat.server.config.auth.Unauthenticated
import org.filemat.server.module.auth.service.AuthTokenService
import org.filemat.server.module.file.model.PlainFolderVisibility
import org.filemat.server.module.file.service.FolderVisibilityService
import org.filemat.server.module.log.model.LogLevel
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.role.service.UserRoleService
import org.filemat.server.module.service.AppService
import org.filemat.server.module.setting.service.SettingService
import org.filemat.server.module.user.model.User
import org.filemat.server.module.user.model.UserAction
import org.filemat.server.module.user.service.UserService
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController


/**
 * Controller that handles initial application setup.
 *
 * Only used when application is first installed.
 */
@BeforeSetup
@Unauthenticated
@RestController
@RequestMapping("/v1/setup")
class SetupController(
    private val passwordEncoder: PasswordEncoder,
    private val userService: UserService,
    private val logService: LogService,
    private val userRoleService: UserRoleService,
    private val appService: AppService,
    private val settingService: SettingService,
    private val authTokenService: AuthTokenService,
    private val folderVisibilityService: FolderVisibilityService,
) : AController() {

    val submitLock = Locker()

    @GetMapping("/sensitive-folders")
    fun getSensitiveFolderListMapping() = ok(Props.sensitiveFolders.serialize())

    @GetMapping("/status")
    fun setupStatusMapping() = ok(State.App.isSetup.toString())

    @PostMapping("/verify")
    fun verifyAppSetupCode(
        request: HttpServletRequest,
        @RequestParam("setup-code") input: String,
    ): ResponseEntity<String> {
        val isAppSetup = settingService.getSetting(Props.Settings.isAppSetup)
        if (isAppSetup.valueOrNull?.value == "true") return bad("Application is already set up.", "already-setup")

        val code = settingService.getSetting(Props.Settings.appSetupCode)
        if (code.hasError) return internal(code.error, "code-failure")
        if (code.isNotSuccessful) return bad(code.error, "code-failure")

        return if (code.value.value == input) ok() else bad("Setup code is incorrect.", "invalid-code")
    }

    /**
     * Sets up the application.
     *
     * Creates the initial admin account.
     */
    @PostMapping("/submit")
    fun submitApplicationSetupMapping(
        request: HttpServletRequest,
        response: HttpServletResponse,
        @RequestParam("email") email: String,
        @RequestParam("username") username: String,
        @RequestParam("password") plainPassword: String,
        @RequestParam("folder-visibility-list") rawFolderVisibilities: String,
        @RequestParam("follow-symlinks") rawFollowSymlinks: String,
        @RequestParam("setup-code") setupCode: String,
    ): ResponseEntity<String> = submitLock.run (default = bad("${Props.appName} is already being set up.", "lock")) {
        if (State.App.isSetup) return@run bad("${Props.appName} has already been set up. You can log in with an admin account.", "already-setup")

        (
            Validator.email(email)
            ?: Validator.password(plainPassword)
            ?: Validator.username(username)
        )?.let { return@run bad(it, "validation") }

        val codeVerification = appService.verifySetupCode(setupCode)
        if (codeVerification.rejected) return@run bad(codeVerification.error, "setup-code-invalid")
        if (codeVerification.isNotSuccessful) return@run internal(codeVerification.error, "code-verification-failure")

        val folderVisibilities = Json.decodeFromStringOrNull<List<PlainFolderVisibility>>(rawFolderVisibilities)
            ?: return@run bad("Configuration for folder visibility is invalid.", "validation")
        val followSymlinks = rawFollowSymlinks.toBooleanStrictOrNull()
            ?: return@run bad("Option for following symbolic links must be true or false.", "validation")

        // Check for folder visibility duplicates
        folderVisibilities.let {
            val normalizedList = mutableSetOf<String>()
            val rawList = mutableSetOf<String>()
            it.forEach { path ->
                val alreadyExists = !rawList.add(path.path) || !normalizedList.add(path.path.normalizePath())
                if (alreadyExists) return@run bad("Folder visibility configuration has a duplicate.", "validation")
            }
        }

        val password = passwordEncoder.encode(plainPassword)
        val now = unixNow()

        val user = User(
            userId = UlidCreator.getUlid(),
            email = email,
            username = username,
            password = password,
            mfaTotpSecret = null,
            mfaTotpStatus = false,
            mfaTotpCodes = null,
            createdDate = now,
            lastLoginDate = null,
            isBanned = false,
        )

        val result = runTransaction { status ->
            // Create user account
            val userResult = userService.createUser(user, UserAction.APP_SETUP)
            if (userResult.isNotSuccessful) {
                status.setRollbackOnly()
                return@runTransaction userResult
            }

            // Give roles to user account
            val roleResult = userRoleService.assign(userId = user.userId, roleId = Props.adminRoleId, action = UserAction.APP_SETUP)
            if (roleResult.isNotSuccessful) {
                status.setRollbackOnly()
                return@runTransaction roleResult
            }

            // Mark app as set up
            settingService.setSetting(Props.Settings.isAppSetup, "true").let { result ->
                if (result.isNotSuccessful) {
                    status.setRollbackOnly()
                    return@runTransaction Result.error("Failed to save setup status to database.")
                }
            }

            // Create setting for following symlinks
            settingService.setSetting(Props.Settings.followSymlinks, followSymlinks.toString()).let { result ->
                if (result.isNotSuccessful) {
                    status.setRollbackOnly()
                    return@runTransaction Result.error("Failed to save configuration for following symbolic links.")
                }
            }

            // Save exposed folders
            val visibilityResult = folderVisibilityService.insertPaths(folderVisibilities, UserAction.APP_SETUP)
            if (visibilityResult.isNotSuccessful) {
                status.setRollbackOnly()
                return@runTransaction Result.error("Failed to save folder visibility configuration to database.")
            }

            return@runTransaction Result.ok(Unit)
        }

        if (result.isNotSuccessful) return@run internal(result.error, "failure")

        logService.createLog(
            level = LogLevel.INFO,
            type = LogType.AUDIT,
            action = UserAction.APP_SETUP,
            createdDate = now,
            description = "Admin account created during application setup.",
            message = "Email: [$email]\nusername: [$username]\naccount ID: [${user.userId}]",
            meta = mapOf("setup-code" to setupCode)
        )

        appService.deleteSetupCode()
        State.App.isSetup = true
        State.App.followSymLinks = followSymlinks

        val tokenR = authTokenService.createToken(user.userId, "", UserAction.APP_SETUP)
        if (tokenR.isSuccessful) {
            val token = tokenR.value
            val cookie = authTokenService.createCookie(token = token.authToken, maxAge = token.maxAge)
            response.addCookie(cookie)
        }

        return@run ok("${Props.appName} was set up. You can log in with your admin account.")
    }

}