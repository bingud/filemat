package org.filemat.server.module.user.controller

import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.Json
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.getPrincipal
import org.filemat.server.module.auth.service.MfaService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v1/user")
class UserController(
    private val mfaService: MfaService,
) : AController() {

    @PostMapping("/mfa/enable/generate-secret")
    fun generateMfaSecretMapping(
        request: HttpServletRequest
    ): ResponseEntity<String> {
        val user = request.getPrincipal()!!
        if (user.mfaTotpStatus) return bad("2FA is already enabled.", "already-enabled")

        val totp = mfaService.generateTotp(user)
        val serialized = Json.encodeToString(totp)

        return ok(serialized)
    }

}