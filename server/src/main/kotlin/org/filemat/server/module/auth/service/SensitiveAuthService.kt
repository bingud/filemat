package org.filemat.server.module.auth.service

import com.github.benmanes.caffeine.cache.Caffeine
import org.filemat.server.common.util.StringUtils
import org.filemat.server.config.Props
import org.springframework.stereotype.Service
import java.io.File
import java.util.concurrent.TimeUnit
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.util.unixNow


/**
 * Service for strict authentication (proof of physical access to the backend)
 */
@Service
class SensitiveAuthService(

) {
    final val maxCodeAge = 300L

    // Code, expirationDate
    private val tokens = Caffeine.newBuilder()
        .expireAfterWrite(maxCodeAge, TimeUnit.SECONDS)
        .maximumSize(100_000)
        .build<String, Long>()


    /**
     * Returns the expiration date of a code (if valid)
     */
    fun verifyOtp(otp: String): Result<Long> {
        val expirationDate = tokens.getIfPresent(otp)
            ?: return Result.reject("Code is invalid.")

        return Result.ok(expirationDate)
    }

    fun createOtp(): Result<Long> {
        val otp = StringUtils.randomString(16).uppercase()
        val expirationDate = unixNow() + maxCodeAge
        tokens.put(otp, expirationDate)

        printOtpToConsole(otp)
        saveOtpToFile(otp).let {
            if (it.isNotSuccessful) return it.cast()
        }

        return Result.ok(expirationDate)
    }

    private fun printOtpToConsole(otp: String) {
        println("************")
        println("Authentication code:")
        println(otp)
        println("************")
    }

    private fun saveOtpToFile(otp: String): Result<Unit> {
        try {
            val file = File(Props.authCodeFile)
            file.writeText(otp)
        } catch (e: Exception) {
            return Result.error("Failed to save code to a file.")
        }
        return Result.ok()
    }
}