package org.filemat.server.module.auth.service

import com.atlassian.onetime.core.TOTPGenerator
import com.atlassian.onetime.model.EmailAddress
import com.atlassian.onetime.model.Issuer
import com.atlassian.onetime.model.TOTPSecret
import com.atlassian.onetime.service.DefaultTOTPService
import com.atlassian.onetime.service.RandomSecretProvider
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.StringUtils
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
    private val logService: LogService
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

    val newMfaCache = Caffeine.newBuilder()
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .maximumSize(100)
        .build<Ulid, Mfa>()

    private val totpService = DefaultTOTPService()
    private val generator = TOTPGenerator()


    fun enable_generateSecret(user: Principal): Mfa {
        newMfaCache.getIfPresent(user.userId)
            ?.let { return it }

        val secret: TOTPSecret = RandomSecretProvider.generateSecret()
        val url: URI = totpService.generateTOTPUrl(secret,
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

    fun enable_confirmSecret(user: Principal, totp: String, codes: List<String>): Result<Unit> {
        val mfa = newMfaCache.getIfPresent(user.userId) ?: return Result.reject("2FA setup has expired.")
        val actualTotp = generator.generateCurrent(mfa.secretObject)
        if (totp != actualTotp.value) return Result.reject("2FA code is incorrect.")

        // Validate if client has valid backup codes
        if (!codes.containsAll(mfa.codes)) return Result.reject("Backup codes could not be validated.")

        updateUserMfa(user.userId, true, mfa.secret, mfa.codes).let {
            if (it.isNotSuccessful) return it
        }

        newMfaCache.invalidate(user.userId)
        return Result.ok()
    }

    fun updateUserMfa(userId: Ulid, status: Boolean, secret: String?, codes: List<String>?): Result<Unit> {
        try {
            val serializedCodes = codes?.toJson()
            userRepository.updateTotpMfa(userId, status, secret, serializedCodes)
            return Result.ok()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.UPDATE_MFA,
                description = "Failed to update totp MFA in the database",
                message = e.stackTraceToString()
            )
            return Result.error("Failed to update 2FA.")
        }
    }
}























