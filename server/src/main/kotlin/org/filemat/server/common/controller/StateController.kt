package org.filemat.server.common.controller

import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.filemat.server.common.State
import org.filemat.server.common.util.JsonBuilder
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.getAuth
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/state")
class StateController : AController() {

    @PostMapping("/select")
    fun optionalStateMapping(
        request: HttpServletRequest,
        @RequestParam("principal", required = false) rawPrincipal: String?,
        @RequestParam("roles", required = false) rawRoles: String?,
        @RequestParam("app", required = false) rawApp: String?,
    ): ResponseEntity<String> {
        val principal = request.getAuth()!!

        val getPrincipal = rawPrincipal?.toBooleanStrictOrNull()
        val getRoles = rawRoles?.toBooleanStrictOrNull()
        val getApp = rawApp?.toBooleanStrictOrNull()
        if (getPrincipal != true && getRoles != true && getApp != true) return bad("Auth state request did not request anything.", "")

        val builder = JsonBuilder()

        // Return user principal
        if (getPrincipal == true) {
            val principalBuilder = JsonBuilder()

            principalBuilder.put("value", Json.encodeToJsonElement(principal))

            builder.put("principal", principalBuilder.build())
        }
        // Return all available roles
        if (getRoles == true) {
            val roleBuilder = JsonBuilder()

            val roles = State.Auth.roleMap.values.toList()
            roleBuilder.put("value", Json.encodeToJsonElement(roles))

            builder.put("roles", roleBuilder.build())
        }
        if (getApp == true) {

        }

        val serialized = builder.toString()
        return ok(serialized)
    }

}