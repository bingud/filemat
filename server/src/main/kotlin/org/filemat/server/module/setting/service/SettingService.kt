package org.filemat.server.module.setting.service

import org.filemat.server.common.model.Result
import org.filemat.server.common.model.toResult
import org.filemat.server.module.setting.model.Setting
import org.filemat.server.module.setting.repository.SettingRepository
import org.springframework.stereotype.Service

@Service
class SettingService(
    private val settingRepository: SettingRepository
) {

    fun getSetting(name: String): Result<Setting> {
        return try {
            val result = settingRepository.getSetting(name)
                ?: return Result.notFound()

            result.toResult()
        } catch (e: Exception) {
            Result.error("Failed to get setting \"$name\" from database.")
        }
    }


}