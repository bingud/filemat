package org.filemat.server.module.admin.controller

import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.Json
import org.filemat.server.common.util.*
import org.filemat.server.common.util.controller.AController
import org.filemat.server.config.auth.Authenticated
import org.filemat.server.module.auth.service.SensitiveAuthService
import org.filemat.server.module.file.model.FileVisibility
import org.filemat.server.module.file.service.FileVisibilityService
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.role.service.RoleService
import org.filemat.server.module.role.service.UserRoleService
import org.filemat.server.module.setting.service.SettingService
import org.filemat.server.module.user.model.UserAction
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController


/**
 * Admin controller for system settings
 */
@Authenticated([SystemPermission.MANAGE_SYSTEM])
@RestController
@RequestMapping("/v1/admin/system")
class AdminSettingsController(
    private val roleService: RoleService,
    private val userRoleService: UserRoleService,
    private val logService: LogService,
    private val settingService: SettingService,
    private val fileVisibilityService: FileVisibilityService,
    private val sensitiveAuthService: SensitiveAuthService,
) : AController() {

    @PostMapping("/add-file-visibility")
    fun adminAddFileVisibilityMapping(
        request: HttpServletRequest,
        @RequestParam("auth_code") authCode: String,
        @RequestParam("path") path: String,
        @RequestParam("isExposed") isExposed: Boolean,
    ): ResponseEntity<String> {
        TODO()

        sensitiveAuthService.verifyOtp(authCode).let {
            if (it.rejected) return unauthenticated(it.error, "invalid-code")
            if (it.hasError) return internal(it.error)
        }

        val fileVisibility = FileVisibility(
            path = path,
            isExposed = isExposed,
            createdDate = unixNow()
        )

        fileVisibilityService.insertPath(fileVisibility, UserAction.ADD_FILE_VISIBILITY_CONFIGURATION).let {
            if (it.isNotSuccessful) return internal(it.error)
        }

        val message = "File visibility configuration added: set to ${if (isExposed) "exposed" else "hidden"}"
        logService.info(
            LogType.AUDIT,
            UserAction.ADD_FILE_VISIBILITY_CONFIGURATION,
            message,
            ""
        )

        return ok("ok")
    }

    @PostMapping("/authenticate-sensitive-code")
    fun adminAuthenticateSensitiveCodeMapping(
        request: HttpServletRequest,
        @RequestParam("code") code: String,
    ): ResponseEntity<String> {
        sensitiveAuthService.verifyOtp(code).let {
            if (it.hasError) return internal(it.error)
            if (it.rejected) return bad(it.error)
            return ok(it.value.toString())
        }
    }

    @PostMapping("/generate-sensitive-code")
    fun adminGenerateSensitiveCodeMapping(
        request: HttpServletRequest,
    ): ResponseEntity<String> {
        sensitiveAuthService.createOtp().let {
            if (it.hasError) return internal(it.error)
            if (it.rejected) return bad(it.error)
            return ok(it.value.toString())
        }
    }

    @GetMapping("/file-visibility-entries")
    fun adminGetFileVisibilityEntriesMapping(
        request: HttpServletRequest,
    ): ResponseEntity<String> {
        val visibilities: Map<String, Boolean> = fileVisibilityService.getAllFileVisibilities()
        val serialized = Json.encodeToString(visibilities)
        return ok(serialized)
    }

    /**
     * Set system setting `followSymLinks`
     */
    @PostMapping("/set/follow-symlinks")
    fun adminGetRoleMapping(
        request: HttpServletRequest,
        @RequestParam("new-state") rawNewState: String,
    ): ResponseEntity<String> {
        val user = request.getPrincipal()!!
        val newState = rawNewState.toBooleanStrictOrNull()
            ?: return bad("Invalid new state. Follow Symlinks setting must be true or false.", "validation")

        settingService.set_followSymLinks(user, newState).let {
            if (it.hasError) return internal(it.error, "")
            if (it.isNotSuccessful) return bad(it.error, "")
            return ok("ok")
        }
    }
}