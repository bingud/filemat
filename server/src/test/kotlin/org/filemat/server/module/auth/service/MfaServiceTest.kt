package org.filemat.server.module.auth.service

import com.atlassian.onetime.model.TOTPSecret
import com.github.f4b6a3.ulid.UlidCreator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.TotpUtil
import org.filemat.server.common.util.dto.RequestMeta
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.model.User
import org.filemat.server.module.user.model.UserAction
import org.filemat.server.module.user.repository.UserRepository
import org.filemat.server.module.user.service.UserService
import org.filemat.server.testutil.assertSuccess
import org.filemat.server.testutil.testPrincipal
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MfaServiceTest {

    private val userService = mockk<UserService>()
    private val userRepository = mockk<UserRepository>()
    private val authService = mockk<AuthService>(relaxed = true)
    private val logService = mockk<LogService>(relaxed = true)
    private val service = MfaService(userService, userRepository, logService, authService)

    private val secret = "JBSWY3DPEHPK3PXP"
    private val userId = UlidCreator.getUlid()
    private val meta = RequestMeta(targetId = userId, action = UserAction.DISABLE_TOTP_MFA)

    private fun userWith2fa() = User(
        userId = userId, email = "a@test", username = "a", password = "", mfaTotpSecret = secret, mfaTotpStatus = true,
        mfaTotpCodes = null, mfaTotpRequired = false, createdDate = 0, lastLoginDate = null, isBanned = false, homeFolderPath = null,
    )

    private fun currentCode() = TotpUtil.generator.generateCurrent(TOTPSecret.fromBase32EncodedString(secret)).value

    @Test
    fun `disable answers an error when the database update fails`() {
        every { userService.getUserByUserId(userId, any()) } returns Result.ok(userWith2fa())
        every { userRepository.updateTotpMfaKeepRequired(any(), any(), any(), any()) } throws RuntimeException("db down")

        val result = service.disable(meta, currentCode())

        assertTrue(result.hasError)
        verify(exactly = 0) { logService.info(any(), any(), "User disabled TOTP 2FA", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `disable succeeds and keeps whether an admin requires 2FA`() {
        every { userService.getUserByUserId(userId, any()) } returns Result.ok(userWith2fa())
        every { userRepository.updateTotpMfaKeepRequired(any(), any(), any(), any()) } returns 1

        assertSuccess(service.disable(meta, currentCode()))
        verify { userRepository.updateTotpMfaKeepRequired(userId, false, null, null) }
        verify(exactly = 0) { userRepository.updateTotpMfa(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `enabling 2FA keeps whether an admin requires it`() {
        every { userRepository.updateTotpMfaKeepRequired(any(), any(), any(), any()) } returns 1
        val credentials = service.enable_generateSecret(testPrincipal(userId = userId))
        val code = TotpUtil.generator.generateCurrent(credentials.secretObject).value

        assertSuccess(service.enable_confirmSecret(meta.copy(action = UserAction.ENABLE_TOTP_MFA), code, credentials.codes))

        verify { userRepository.updateTotpMfaKeepRequired(userId, true, credentials.secret, any()) }
        verify(exactly = 0) { userRepository.updateTotpMfa(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an admin can stop requiring 2FA without resetting it`() {
        every { userRepository.updateTotpMfaRequired(any(), any()) } returns 1

        assertSuccess(service.setRequired(meta.copy(action = UserAction.SET_TOTP_MFA_REQUIRED), false))

        verify { userRepository.updateTotpMfaRequired(userId, false) }
        verify(exactly = 0) { userRepository.updateTotpMfa(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `requiring 2FA for a user that doesn't exist is not found`() {
        every { userRepository.updateTotpMfaRequired(any(), any()) } returns 0

        assertTrue(service.setRequired(meta, true).notFound)
    }
}
