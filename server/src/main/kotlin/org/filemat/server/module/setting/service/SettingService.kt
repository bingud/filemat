package org.filemat.server.module.setting.service

import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.unixNow
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.setting.model.Setting
import org.filemat.server.module.setting.repository.SettingRepository
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service

@Service
class SettingService(
    private val settingRepository: SettingRepository,
    private val logService: LogService
) {

    /**
     * Set the setting `followSymLinks`
     */
    fun set_followSymLinks(user: Principal, newState: Boolean): Result<Unit> {
        db_setSetting(Props.Settings.followSymlinks, newState.toString()).let {
            if (it.isNotSuccessful) return it.cast()
        }

        State.App.followSymlinks = newState

        logService.info(
            type = LogType.AUDIT,
            action = UserAction.UPDATE_SYSTEM_SETTING,
            description = "Symlinks ${if (newState) "enabled" else "disabled"}",
            message = "User ${user.username} toggled system setting ${Props.Settings.followSymlinks} to $newState",
            initiatorId = user.userId,
        )
        return Result.ok()
    }

    fun getSetting(name: String): Result<Setting> {
        return try {
            val result = settingRepository.getSetting(name)
                ?: return Result.notFound()

            result.toResult()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.NONE,
                description = "Failed to get setting from database. Setting name: [$name]",
                message = e.stackTraceToString()
            )
            Result.error("Failed to get setting from database.")
        }
    }

    fun db_setSetting(name: String, value: String): Result<Unit> {
        return try {
            settingRepository.setSetting(name, value, unixNow())
            Result.ok(Unit)
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.NONE,
                description = "Failed to save setting in database. Setting name: [$name]",
                message = e.stackTraceToString()
            )
            Result.error("Failed to save setting in database.")
        }
    }

    fun removeSetting(name: String): Result<Unit> {
        return try {
            settingRepository.getSetting(name)
            Result.ok(Unit)
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.NONE,
                description = "Failed to remove setting from database. Setting name: [$name]",
                message = e.stackTraceToString()
            )
            Result.error("Failed to remove setting from database.")
        }
    }

}