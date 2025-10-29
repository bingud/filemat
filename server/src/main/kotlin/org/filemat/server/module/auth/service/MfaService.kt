package org.filemat.server.module.auth.service

import com.atlassian.onetime.model.EmailAddress
import com.atlassian.onetime.model.Issuer
import com.atlassian.onetime.model.TOTPSecret
import com.atlassian.onetime.service.RandomSecretProvider
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.util.StringUtils
import org.filemat.server.common.util.TotpUtil
import org.filemat.server.common.util.dto.RequestMeta
import org.filemat.server.common.util.toJson
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.model.UserAction
import org.filemat.server.module.user.repository.UserRepository
import org.filemat.server.module.user.service.UserService
import org.springframework.stereotype.Service
import java.net.URI
import java.util.concurrent.TimeUnit

@Service
class MfaService(
    private val userService: UserService,
    private val userRepository: UserRepository,
    private val logService: LogService,
    private val authService: AuthService
) {

    @Serializable
    data class Mfa(
        @Transient
        val secretObject: TOTPSecret = null!!,
        @Transient
        val urlObject: URI = null!!,
        val secret: String,
        val url: String,
        val codes: List<String>,
    )

    // Cache for TOTP secrets (when user is enabling 2FA)
    val newMfaCache = Caffeine.newBuilder()
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .maximumSize(100_000)
        .build<Ulid, Mfa>()


    /**
     * Prepares a TOTP secret for user to enable 2FA
     */
    fun enable_generateSecret(user: Principal): Mfa {
        newMfaCache.getIfPresent(user.userId)
            ?.let { return it }

        val secret: TOTPSecret = RandomSecretProvider.generateSecret()
        val url: URI = TotpUtil.totpService.generateTOTPUrl(secret,
            EmailAddress(user.email),
            Issuer(Props.appName)
        )
        val codes = List(6) { StringUtils.randomString(8).uppercase() }

        val newTotpSecret = Mfa(
            secret,
            url,
            secret.base32Encoded,
            url.toString(),
            codes
        )
        newMfaCache.put(user.userId, newTotpSecret)
        return newTotpSecret
    }

    /**
     * Enables 2FA on user account
     */
    fun enable_confirmSecret(meta: RequestMeta, totp: String, codes: List<String>): Result<Unit> {
        val mfa = newMfaCache.getIfPresent(meta.userId) ?: return Result.reject("2FA setup has expired.")
        val actualTotp = TotpUtil.generator.generateCurrent(mfa.secretObject)
        if (totp != actualTotp.value) return Result.reject("2FA code is incorrect.")

        // Validate if client has valid backup codes
        if (!codes.containsAll(mfa.codes)) return Result.reject("Backup codes could not be validated.")

        updateUserMfa(meta, true, mfa.secret, mfa.codes, false).let {
            if (it.isNotSuccessful) return it
        }

        logService.info(
            type = LogType.AUDIT,
            action = UserAction.ENABLE_TOTP_MFA,
            description = "User enabled TOTP 2FA",
            message = "",
            initiatorId = meta.userId,
            initiatorIp = null,
            targetId = null,
            meta = null
        )

        newMfaCache.invalidate(meta.userId)
        return Result.ok()
    }

    fun disable(meta: RequestMeta, totp: String): Result<Unit> {
        val user = userService.getUserByUserId(meta.userId, meta.action).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }
        if (!user.mfaTotpStatus) return Result.ok()

        val secretString = user.mfaTotpSecret!!
        val secret = TOTPSecret.fromBase32EncodedString(secretString)
        val isValid = TotpUtil.verify(secret, totp)
        if (!isValid) return Result.reject("2FA code is incorrect.")

        updateUserMfa(
            meta = meta,
            status = false,
            secret = null,
            codes = null,
            isRequired = false
        )

        logService.info(
            type = LogType.AUDIT,
            action = meta.action,
            description = "User disabled TOTP 2FA",
            message = "",
            initiatorId = meta.userId,
            initiatorIp = null,
            targetId = null,
            meta = null
        )

        return Result.ok()
    }

    /**
     * Updates 2FA state of a user
     */
    fun updateUserMfa(
        meta: RequestMeta,
        status: Boolean,
        secret: String?,
        codes: List<String>?,
        isRequired: Boolean,
    ): Result<Unit> {
        try {
            val serializedCodes = codes?.toJson()
            userRepository.updateTotpMfa(meta.userId, status, secret, serializedCodes, isRequired)
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = meta.action,
                description = "Failed to update totp MFA in the database",
                message = e.stackTraceToString()
            )
            return Result.error("Failed to update 2FA.")
        }

        authService.updatePrincipal(meta.userId) { existing ->
            existing.copy(mfaTotpStatus = status)
        }

        return Result.ok()
    }
}























