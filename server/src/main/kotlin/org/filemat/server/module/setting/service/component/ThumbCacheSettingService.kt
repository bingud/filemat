package org.filemat.server.module.setting.service.component

import org.filemat.server.common.State
import org.filemat.server.config.Props
import org.filemat.server.module.setting.service.SettingService
import org.springframework.stereotype.Service

@Service
class ThumbCacheSettingService(
    private val settingService: SettingService
) {

    fun initializeSettings() {
        // isEnabled
        settingService.getSetting(Props.Settings.ThumbCache.enabled).let { result ->
            State.ThumbCache.isEnabled = result.valueOrNull?.value?.toBooleanStrictOrNull() ?: false
        }

        // Folder path
        settingService.getSetting(Props.Settings.ThumbCache.enabled).let { result ->
            State.ThumbCache.folderPath = result.valueOrNull?.value
        }

        // Max Size
        settingService.getSetting(Props.Settings.ThumbCache.maxSizeMb).let { result ->
            State.ThumbCache.maxSizeMb = result.valueOrNull?.value?.toIntOrNull()
        }

        // Folder path
        settingService.getSetting(Props.Settings.ThumbCache.maxAge).let { result ->
            State.ThumbCache.maxAge = result.valueOrNull?.value?.toIntOrNull()
        }
    }

}