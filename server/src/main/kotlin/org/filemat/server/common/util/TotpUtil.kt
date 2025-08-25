package org.filemat.server.common.util

import com.atlassian.onetime.core.TOTPGenerator
import com.atlassian.onetime.service.DefaultTOTPService


object TotpUtil {

    val totpService = DefaultTOTPService()
    val generator = TOTPGenerator()

}