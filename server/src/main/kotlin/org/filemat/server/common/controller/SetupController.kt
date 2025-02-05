package org.filemat.server.common.controller

import com.github.f4b6a3.ulid.UlidCreator
import jakarta.servlet.http.HttpServletRequest
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.AController
import org.filemat.server.common.util.Validator
import org.filemat.server.common.util.runTransaction
import org.filemat.server.common.util.unixNow
import org.filemat.server.config.Props
import org.filemat.server.module.log.model.LogLevel
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.role.service.UserRoleService
import org.filemat.server.module.user.model.User
import org.filemat.server.module.user.model.UserAction
import org.filemat.server.module.user.service.UserService
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v1/setup")
class SetupController(
    private val passwordEncoder: PasswordEncoder,
    private val transactionTemplate: TransactionTemplate,
    private val userService: UserService,
    private val logService: LogService,
    private val userRoleService: UserRoleService,
) : AController() {

    /**
     * Sets up the application.
     *
     * Creates the initial admin account.
     */
    @PostMapping("/submit")
    fun submitApplicationSetupMapping(
        request: HttpServletRequest,
        @RequestParam("email") email: String,
        @RequestParam("username") username: String,
        @RequestParam("password") plainPassword: String,
    ): ResponseEntity<String> {
        if (State.App.isSetup == true) return bad("${Props.appName} has already been set up. You can log in with an admin account.")

        (
            Validator.email(email)
            ?: Validator.password(plainPassword)
            ?: Validator.username(username)
        )?.let { return bad(it) }

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
            val userResult = userService.createUser(user, UserAction.APP_SETUP)
            if (userResult.isNotSuccessful) {
                status.setRollbackOnly()
                return@runTransaction userResult
            }

            val roleResult = userRoleService.assign(userId = user.userId, roleId = Props.adminRoleId, action = UserAction.APP_SETUP)
            if (roleResult.isNotSuccessful) {
                status.setRollbackOnly()
                return@runTransaction roleResult
            }

            val logResult = logService.createLog(
                level = LogLevel.INFO,
                type = LogType.AUDIT,
                action = UserAction.APP_SETUP,
                createdDate = now,
                description = "Admin account created during application setup.",
                message = "Email: [$email]\nusername: [$username]\naccount ID: [${user.userId}]",
            )

            if (!logResult) {
                status.setRollbackOnly()
                return@runTransaction Result.error("Could not finish setup. Server failed to create a log entry.")
            }

            return@runTransaction Result.ok(Unit)
        }

        if (result.isNotSuccessful) return internal(result.error)

        return ok("${Props.appName} was set up. You can log in with your admin account.")
    }

}