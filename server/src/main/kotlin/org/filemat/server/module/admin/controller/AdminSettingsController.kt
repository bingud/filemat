package org.filemat.server.module.admin.controller

import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.Json
import org.filemat.server.common.util.*
import org.filemat.server.common.util.controller.AController
import org.filemat.server.config.auth.Authenticated
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.role.model.RoleMeta
import org.filemat.server.module.role.service.RoleService
import org.filemat.server.module.role.service.UserRoleService
import org.filemat.server.module.setting.service.SettingService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController


/**
 * Admin controller for managing roles
 */
@Authenticated([SystemPermission.MANAGE_SYSTEM])
@RestController
@RequestMapping("/v1/admin/system")
class AdminSettingsController(
    private val roleService: RoleService,
    private val userRoleService: UserRoleService,
    private val logService: LogService,
    private val settingService: SettingService,
) : AController() {

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