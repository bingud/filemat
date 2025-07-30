package org.filemat.server.module.auth.service

import com.atlassian.onetime.model.EmailAddress
import com.atlassian.onetime.model.Issuer
import com.atlassian.onetime.model.TOTPSecret
import com.atlassian.onetime.service.DefaultTOTPService
import com.atlassian.onetime.service.RandomSecretProvider
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.Serializable
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.Principal
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class MfaService {

    @Serializable
    data class NewTotp(
        val totp: String,
        val url: String,
    )

    val newTotpCache = Caffeine.newBuilder()
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .maximumSize(100)
        .build<Ulid, NewTotp>()

    private val totpService = DefaultTOTPService()


    fun generateTotp(user: Principal): NewTotp {
        newTotpCache.getIfPresent(user.userId)
            ?.let { return it }

        val secret: TOTPSecret = RandomSecretProvider.generateSecret()
        val url = totpService.generateTOTPUrl(secret,
            EmailAddress(user.email),
            Issuer(Props.appName)
        )

        val newTotp = NewTotp(
            secret.base32Encoded,
            url.path
        )
        newTotpCache.put(user.userId, newTotp)
        return newTotp
    }

}