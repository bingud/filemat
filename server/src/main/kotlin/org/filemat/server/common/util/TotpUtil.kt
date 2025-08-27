package org.filemat.server.common.util

import com.atlassian.onetime.core.TOTPGenerator
import com.atlassian.onetime.model.TOTPSecret
import com.atlassian.onetime.service.DefaultTOTPService


object TotpUtil {

    val totpService = DefaultTOTPService()
    val generator = TOTPGenerator()

    /**
     * Verifies a TOTP with 1 step tolerance
     */
    fun verify(secret: TOTPSecret, totp: String): Boolean {
        val validTotps = TotpUtil.generator.generate(secret, delaySteps = 1, futureSteps = 1)
        return validTotps.any { it.value == totp }
    }

}