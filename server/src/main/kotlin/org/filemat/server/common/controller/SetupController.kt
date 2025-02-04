package org.filemat.server.common.controller

import com.github.f4b6a3.ulid.UlidCreator
import jakarta.servlet.http.HttpServletRequest
import org.filemat.server.common.State
import org.filemat.server.common.util.AController
import org.filemat.server.common.util.Validator
import org.filemat.server.common.util.runTransaction
import org.filemat.server.common.util.unixNow
import org.filemat.server.config.Props
import org.filemat.server.module.user.model.User
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.PlatformTransactionManager
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

        val user = User(
            userId = UlidCreator.getUlid(),
            email = email,
            username = username,
            password = password,
            mfaTotpSecret = null,
            mfaTotpStatus = false,
            mfaTotpCodes = null,
            createdDate = unixNow(),
            lastLoginDate = null,
            isBanned = false,
        )

        runTransaction { status ->
            save user,
            make audit log
        }

        return ok()
    }

}