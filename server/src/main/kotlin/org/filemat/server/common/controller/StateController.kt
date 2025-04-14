package org.filemat.server.common.controller

import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.filemat.server.common.State
import org.filemat.server.common.util.JsonBuilder
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.getPackage
import org.filemat.server.common.util.getPrincipal
import org.filemat.server.common.util.measureMillis
import org.filemat.server.config.Props
import org.filemat.server.config.auth.BeforeSetup
import org.filemat.server.config.auth.Unauthenticated
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Controller that serves generic frontend state.
 *
 * User auth principal, application state, available roles.
 */
@BeforeSetup
@RestController
@RequestMapping("/v1/state")
class StateController : AController() {

    @Unauthenticated
    @PostMapping("/select")
    fun optionalStateMapping(
        request: HttpServletRequest,
        @RequestParam("principal", required = false) rawPrincipal: String?,
        @RequestParam("roles", required = false) rawRoles: String?,
        @RequestParam("systemRoleIds", required = false) rawrSysRoleIds: String?,
        @RequestParam("app", required = false) rawApp: String?,
    ): ResponseEntity<String> {
        Exception().printStackTrace()

        val principal = request.getPrincipal()

        val getPrincipal = rawPrincipal?.toBooleanStrictOrNull()
        val getRoles = rawRoles?.toBooleanStrictOrNull()
        val getApp = rawApp?.toBooleanStrictOrNull()
        val getSystemRoleIds = rawrSysRoleIds?.toBooleanStrictOrNull()

        val builder = JsonBuilder()

        // Return user principal
        if (getPrincipal == true) {
            val principalBuilder = JsonBuilder()

            if (principal != null) {
                principalBuilder.put("value", Json.encodeToJsonElement(principal))
                principalBuilder.put("status", 200)
            } else {
                principalBuilder.put("status", 401)
            }

            builder.put("principal", principalBuilder.build())
        }
        // Return all available roles
        if (getRoles == true) {
            val roleBuilder = JsonBuilder()

            if (principal != null) {
                val roles = State.Auth.roleMap.values.toList()
                roleBuilder.put("value", Json.encodeToJsonElement(roles))
                roleBuilder.put("status", 200)
            } else {
                roleBuilder.put("status", 401)
            }

            builder.put("roles", roleBuilder.build())
        }
        if (getApp == true) {
            val valueBuilder = JsonBuilder()
            valueBuilder.put("isSetup", State.App.isSetup)

            val appBuilder = JsonBuilder()
            appBuilder.put("status", 200)
            appBuilder.put("value", valueBuilder.build())

            builder.put("app", appBuilder.build())
        }
        if (getSystemRoleIds == true) {
            val valueBuilder = JsonBuilder()

            val roleBuilder = JsonBuilder()
            roleBuilder.put("user", Props.Roles.userRoleIdString)
            roleBuilder.put("admin", Props.Roles.adminRoleIdString)

            valueBuilder.put("value", roleBuilder.build())
            valueBuilder.put("status", 200)

            builder.put("systemRoleIds", valueBuilder.build())
        }


        val serialized = builder.toString()
        return ok(serialized)
    }

}