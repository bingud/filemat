package org.filemat.server.module.service

import org.filemat.server.common.model.Result
import org.filemat.server.common.util.StringUtils
import org.filemat.server.config.Props
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.setting.service.SettingService
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.nio.file.attribute.UserPrincipalLookupService

/**
 * Service for application utilities
 */
@Service
class AppService(private val settingService: SettingService, private val logService: LogService) {

    fun generateSetupCode() {
        val code = StringUtils.randomLetterString(12).uppercase()

        val op = settingService.db_setSetting(Props.Settings.appSetupCode, code)
        if (op.isNotSuccessful) {
            println("Failed to generate setup code.")
            return
        }

        // Save setup code to file
        try {
            val path = Paths.get(Props.setupCodeFile)
            Files.deleteIfExists(path)

            val perms = PosixFilePermissions.fromString("rwxrwx---")
            val attr = PosixFilePermissions.asFileAttribute(perms)

            Files.createFile(path, attr)
            Files.write(path, code.toByteArray(), StandardOpenOption.WRITE)
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.NONE,
                description = "Failed to save setup code to file (${Props.setupCodeFile})",
                message = e.stackTraceToString()
            )
        }

        println("\n\n\n**********************\nSETUP CODE:\n$code\n**********************")
    }

    fun deleteSetupCode() {
        settingService.removeSetting(Props.Settings.appSetupCode)

        runCatching {
            val path = Paths.get(Props.setupCodeFile)
            Files.deleteIfExists(path)
        }.getOrElse {
            println("Failed to delete setup code file (${Props.setupCodeFile})")
            it.printStackTrace()
        }
    }

    fun verifySetupCode(input: String): Result<Unit> {
        val codeResult = settingService.getSetting(Props.Settings.appSetupCode)
        if (codeResult.isSuccessful) {
            val code = codeResult.value
            if (code.value == input) return Result.ok(Unit)
            return Result.reject("Setup code is incorrect.")
        }

        if (codeResult.notFound) return Result.error("There is no setup code available.")
        return Result.error(codeResult.error)
    }


}