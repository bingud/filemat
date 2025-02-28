package org.filemat.server.common.controller

import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.filemat.server.common.State
import org.filemat.server.common.util.JsonBuilder
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.getAuth
import org.filemat.server.config.auth.Unauthenticated
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/state")
class StateController : AController() {

    @Unauthenticated
    @PostMapping("/select")
    fun optionalStateMapping(
        request: HttpServletRequest,
        @RequestParam("principal", required = false) rawPrincipal: String?,
        @RequestParam("roles", required = false) rawRoles: String?,
        @RequestParam("app", required = false) rawApp: String?,
    ): ResponseEntity<String> {
        val principal = request.getAuth()

        val getPrincipal = rawPrincipal?.toBooleanStrictOrNull()
        val getRoles = rawRoles?.toBooleanStrictOrNull()
        val getApp = rawApp?.toBooleanStrictOrNull()
        if (getPrincipal != true && getRoles != true && getApp != true) return bad("Auth state request did not request anything.", "")

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

        val serialized = builder.toString()
        return ok(serialized)
    }

}