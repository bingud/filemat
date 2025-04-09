package org.filemat.server.module.admin.controller

import com.fasterxml.jackson.module.kotlin.jsonMapper
import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.Json
import org.filemat.server.common.util.*
import org.filemat.server.common.util.classes.ArgonHash
import org.filemat.server.common.util.controller.AController
import org.filemat.server.config.auth.Authenticated
import org.filemat.server.module.admin.service.AdminUserService
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.role.service.RoleService
import org.filemat.server.module.role.service.UserRoleService
import org.filemat.server.module.user.model.FullPublicUser
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
    private val roleService: RoleService,
    private val userRoleService: UserRoleService,
    private val passwordEncoder: PasswordEncoder
) : AController() {

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
            creator = principal,
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
     * Returns a list of mini users
     */
    @PostMapping("/minilist")
    fun adminUserMiniListMapping(
        @RequestParam("userIdList", required = false) rawIdList: String?,
        @RequestParam("allUsers", required = false) rawAllUsers: String?,
    ): ResponseEntity<String> {
        val allUsers = rawAllUsers?.toBooleanStrictOrNull() ?: false
        if (allUsers && rawIdList != null) return bad("User ID list is present when loading all users.", "validation")

        val userIds = if (rawIdList != null) {
            Json.decodeFromStringOrNull<List<String>>(rawIdList)?.map {
                parseUlidOrNull(it) ?: return bad("List of user IDs contains an invalid ID.", "validation")
            } ?: return bad("List of user IDs is invalid.", "validation")
        } else null

        val list = adminUserService.getUserMiniList(userIds, allUsers).let {
            if (it.isNotSuccessful) return internal(it.error, "")
            it.value
        }
        val serialized = Json.encodeToString(list)
        return ok(serialized)
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