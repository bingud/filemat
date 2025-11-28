package org.filemat.server.module.admin.controller

import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.Json
import org.filemat.server.common.util.*
import org.filemat.server.common.util.dto.ArgonHash
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.dto.RequestMeta
import org.filemat.server.config.auth.Authenticated
import org.filemat.server.module.admin.service.AdminUserService
import org.filemat.server.module.auth.service.AuthService
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.role.service.RoleService
import org.filemat.server.module.role.service.UserRoleService
import org.filemat.server.module.user.model.FullPublicUser
import org.filemat.server.module.user.model.UserAction
import org.filemat.server.module.user.service.UserService
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController


/**
 * Admin controller for managing users
 */
@Authenticated([SystemPermission.MANAGE_USERS])
@RequestMapping("/v1/admin/user")
@RestController
class AdminUserController(
    private val adminUserService: AdminUserService,
    private val userRoleService: UserRoleService,
    private val passwordEncoder: PasswordEncoder,
) : AController() {

    @PostMapping("/edit-property")
    fun adminEditUserPropertyMapping(
        request: HttpServletRequest,
        @RequestParam("userId") rawUserId: String,
        @RequestParam("property") property: String,
        @RequestParam("value") value: String,
    ): ResponseEntity<String> {
        val userId = parseUlidOrNull(rawUserId)
            ?: return bad("User ID is invalid.")

        val admin = request.getPrincipal()!!
        val ip = request.realIp()

        val meta = RequestMeta(
            targetId = userId,
            action = UserAction.UPDATE_ACCOUNT_PROPERTY,
            initiatorId = admin.userId,
            ip = ip
        )

        adminUserService.updateProperty(meta, property, value).let {
            if (it.hasError) return internal(it.error)
            if (it.isNotSuccessful) return bad(it.error)
            return ok("ok")
        }
    }

    @PostMapping("/reset-totp-mfa")
    fun adminResetUserMfaMapping(
        request: HttpServletRequest,
        @RequestParam("userId") rawUserId: String,
        @RequestParam("enforce") rawEnforce: String,
    ): ResponseEntity<String> {
        val admin = request.getPrincipal()!!
        val meta = RequestMeta(
            targetId = parseUlidOrNull(rawUserId) ?: return bad("Invalid user ID"),
            initiatorId = admin.userId,
            ip = request.realIp(),
            action = UserAction.RESET_TOTP_MFA
        )

        val enforce = rawEnforce.toBooleanStrictOrNull()
            ?: return bad("Option for 2FA enforcement is missing.")

        adminUserService.resetTotpMfa(meta, enforce).let {
            if (it.rejected) return bad(it.error)
            if (it.hasError) return internal(it.error)
            if (it.notFound) return bad("This user was not found.")
            return ok("ok")
        }
    }

    @PostMapping("/change-password")
    fun adminChangeUserPasswordMapping(
        request: HttpServletRequest,
        @RequestParam("userId") rawUserId: String,
        @RequestParam("password") password: String,
        @RequestParam("logout") rawLogout: String,
    ): ResponseEntity<String> {
        val admin = request.getPrincipal()!!
        val ip = request.realIp()
        val userId = parseUlidOrNull(rawUserId) ?: return bad("Invalid user ID")
        val logout = rawLogout.toBooleanStrictOrNull() ?: return bad("Invalid logout option")

        adminUserService.changeUserPassword(
            adminId = admin.userId,
            adminIp = ip,
            userId = userId,
            rawPassword = password,
            logout = logout,
            userAction = UserAction.CHANGE_PASSWORD
        ).let {
            if (it.rejected) return bad(it.error)
            if (it.hasError) return internal(it.error)
            if (it.notFound) return bad("This user was not found.")
            return ok("ok")
        }
    }

    @PostMapping("/create")
    fun adminCreateUserMapping(
        request: HttpServletRequest,
        @RequestParam("email") rawEmail: String,
        @RequestParam("username") username: String,
        @RequestParam("password") rawPassword: String,
    ): ResponseEntity<String> {
        val principal = request.getPrincipal()!!
        val email = StringUtils.normalizeEmail(rawEmail)

        Validator.email(email)
            ?: Validator.username(username)
            ?: Validator.password(rawPassword)
            ?.let { return bad(it, "validation") }

        val password = passwordEncoder.encode(rawPassword)

        adminUserService.createUser(
            admin = principal,
            email = email,
            username = username,
            password = ArgonHash(password = password)
        ).let {
            if (it.hasError) return internal(it.error, "")
            if (it.isNotSuccessful) return bad(it.error, "")

            val r = json { put("userId", it.value.toString()) }
            return ok(r)
        }
    }



    /**
     * Returns a list of all users
     */
    @PostMapping("/list")
    fun adminUserListMapping(
        request: HttpServletRequest
    ): ResponseEntity<String> {
        val principal = request.getPrincipal()!!

        val result = adminUserService.getUserList()
        if (result.isNotSuccessful) return internal(result.error, "")
        val list = result.value
        val serialized = Json.encodeToString(list)

        return ok(serialized)
    }

    /**
     * Returns a user by the user ID
     */
    @PostMapping("/get")
    fun adminGetUserMapping(
        @RequestParam("userId") rawUserId: String,
    ): ResponseEntity<String> {
        val userId = parseUlidOrNull(rawUserId) ?: return bad("User ID is in an invalid format.", "validation")

        val user = adminUserService.getUser(userId).let {
            if (it.notFound) return notFound()
            if (it.isNotSuccessful) return internal(it.error, "")
            it.value
        }

        val roles = userRoleService.getRolesByUserId(userId).let {
            if (it.isNotSuccessful) return internal(it.error, "")
            it.value
        }

        val fullUser = FullPublicUser.from(user, roles.map { it.roleId })
        val serialized = Json.encodeToString(fullUser)

        return ok(serialized)
    }

}