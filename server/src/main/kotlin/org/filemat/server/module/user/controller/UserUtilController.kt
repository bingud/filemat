package org.filemat.server.module.user.controller

import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.Json
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.decodeFromStringOrNull
import org.filemat.server.common.util.getPrincipal
import org.filemat.server.common.util.parseUlidOrNull
import org.filemat.server.module.auth.model.Principal.Companion.hasPermission
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.user.service.UserUtilService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/user")
class UserUtilController(private val userUtilService: UserUtilService) : AController() {

    /**
     * Returns a list of mini users
     */
    @PostMapping("/minilist")
    fun adminUserMiniListMapping(
        request: HttpServletRequest,
        @RequestParam("userIdList", required = false) rawIdList: String?,
        @RequestParam("allUsers", required = false) rawAllUsers: String?,
    ): ResponseEntity<String> {
        val principal = request.getPrincipal()!!
        if (!principal.hasPermission(SystemPermission.MANAGE_OWN_FILE_PERMISSIONS)) return bad("You do not have permission to list users.")

        val allUsers = rawAllUsers?.toBooleanStrictOrNull() ?: false
        if (allUsers && rawIdList != null) return bad("User ID list is present when loading all users.", "validation")

        val userIds = if (rawIdList != null) {
            Json.decodeFromStringOrNull<List<String>>(rawIdList)?.map {
                parseUlidOrNull(it) ?: return bad("List of user IDs contains an invalid ID.", "validation")
            } ?: return bad("List of user IDs is invalid.", "validation")
        } else null

        val list = userUtilService.getUserMiniList(userIds, allUsers).let {
            if (it.isNotSuccessful) return internal(it.error, "")
            it.value
        }
        val serialized = Json.encodeToString(list)
        return ok(serialized)
    }

}