package org.filemat.server.common.controller

import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.filemat.server.common.State
import org.filemat.server.common.util.JsonBuilder
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.getPrincipal
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
        @RequestParam("systemRoleIds", required = false) rawSysRoleIds: String?,
        @RequestParam("app", required = false) rawApp: String?,
        @RequestParam("followSymlinks", required = false) rawFollowSymlinks: String?,
    ): ResponseEntity<String> {
        val principal = request.getPrincipal()

        val getPrincipal = rawPrincipal?.toBooleanStrictOrNull()
        val getRoles = rawRoles?.toBooleanStrictOrNull()
        val getApp = rawApp?.toBooleanStrictOrNull()
        val getSystemRoleIds = rawSysRoleIds?.toBooleanStrictOrNull()

        val builder = JsonBuilder()

        builder.apply {
            putResponseField("principal", getPrincipal) { principal?.let { Json.encodeToJsonElement(it) } }
            putResponseField("roles", getRoles) { principal?.let { Json.encodeToJsonElement(State.Auth.roleMap.values.toList()) } }

            // for “app” and “systemRoleIds” you need a custom inner object:
            putResponseField("app", getApp) {
                JsonBuilder().apply {
                    put("isSetup", State.App.isSetup)
                    put("followSymlinks", State.App.followSymLinks)
                }.build()
            }

            putResponseField("systemRoleIds", getSystemRoleIds) {
                JsonBuilder().apply {
                    put("user",  Props.Roles.userRoleIdString)
                    put("admin", Props.Roles.adminRoleIdString)
                }.build()
            }
        }

        val serialized = builder.toString()
        return ok(serialized)
    }

}


inline fun JsonBuilder.putResponseField(
    name: String,
    include: Boolean?,
    valueProvider: () -> JsonElement?
) {
    if (include != true) return

    val jsonBody = JsonBuilder().apply {
        val value = valueProvider()
        if (value != null) {
            put("value", value)
            put("status", 200)
        } else {
            put("status", 401)
        }
    }

    put(name, jsonBody.build())
}
