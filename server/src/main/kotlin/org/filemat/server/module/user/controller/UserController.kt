package org.filemat.server.module.user.controller

import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.Json
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.decodeFromStringOrNull
import org.filemat.server.common.util.dto.RequestMeta
import org.filemat.server.common.util.getPrincipal
import org.filemat.server.common.util.realIp
import org.filemat.server.module.auth.service.MfaService
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.user.model.UserAction
import org.filemat.server.module.user.service.UserService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController


/**
 * Controller for user account actions
 */
@RestController
@RequestMapping("/v1/user")
class UserController(
    private val mfaService: MfaService,
    private val userService: UserService,
) : AController() {

    @PostMapping("/update-home-folder-path")
    fun setHomeFolderPathMapping(
        request: HttpServletRequest,
        @RequestParam("path") newPathStr: String,
    ): ResponseEntity<String> {
        val user = request.getPrincipal()!!
        val newPath = FilePath.of(newPathStr)

        val meta = RequestMeta(
            initiatorId = user.userId,
            targetId = user.userId,
            ip = request.realIp(),
            action = UserAction.UPDATE_HOME_FOLDER_PATH,
            principal = user
        )

        // Pass plaintext input path
        userService.changeHomeFolderPath(meta, newPath).let {
            if (it.hasError) return internal(it.error)
            if (it.rejected) return bad(it.error)
            return ok(it.value)
        }
    }

    @PostMapping("/mfa/disable/confirm")
    fun disableMfaMapping(
        request: HttpServletRequest,
        @RequestParam("totp") totp: String,
    ): ResponseEntity<String> {
        val user = request.getPrincipal()!!
        val meta = RequestMeta(
            targetId = user.userId,
            action = UserAction.DISABLE_TOTP_MFA
        )

        mfaService.disable(meta = meta, totp = totp).let {
            if (it.hasError) return internal(it.error)
            if (it.rejected) return bad(it.error)
            return ok("ok")
        }
    }

    /**
     * Generates TOTP secret, prepares user to enable 2FA
     */
    @PostMapping("/mfa/enable/generate-secret")
    fun enableMfa_generateSecretMapping(
        request: HttpServletRequest
    ): ResponseEntity<String> {
        val user = request.getPrincipal()!!
        if (user.mfaTotpStatus) return bad("2FA is already enabled.", "already-enabled")

        val credentials = mfaService.enable_generateSecret(user)
        val serialized = Json.encodeToString(credentials)

        return ok(serialized)
    }

    /**
     * Enables 2FA on user account
     */
    @PostMapping("/mfa/enable/confirm")
    fun enableMfa_confirmMapping(
        request: HttpServletRequest,
        @RequestParam("totp") totp: String,
        @RequestParam("codes") rawCodes: String,
    ): ResponseEntity<String> {
        val user = request.getPrincipal()!!
        val meta = RequestMeta(
            targetId = user.userId,
            action = UserAction.ENABLE_TOTP_MFA
        )

        val codes = Json.decodeFromStringOrNull<List<String>>(rawCodes) ?: return bad("Backup codes could not be validated.", "")
        mfaService.enable_confirmSecret(meta, totp, codes).let {
            if (it.hasError) return internal(it.error, "")
            if (it.rejected) return bad(it.error, "")
            return ok("ok")
        }
    }
}