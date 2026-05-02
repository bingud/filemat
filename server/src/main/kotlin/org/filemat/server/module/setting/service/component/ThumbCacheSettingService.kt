package org.filemat.server.module.setting.service.component

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.util.formatSecondsToReadableTime
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.file.ThumbnailService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.setting.service.SettingService
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service

@Service
class ThumbCacheSettingService(
    private val settingService: SettingService,
    private val filesystemService: FilesystemService,
    private val thumbnailService: ThumbnailService,
    private val logService: LogService,
) {

    fun initializeSettings() {
        // isEnabled
        settingService.getSetting(Props.Settings.ThumbCache.enabled).let { result ->
            State.ThumbCache.isEnabled = result.valueOrNull?.value?.toBooleanStrictOrNull() ?: false
        }

        // Folder path
        settingService.getSetting(Props.Settings.ThumbCache.folderPath).let { result ->
            State.ThumbCache.folderPath = result.valueOrNull?.value
        }

        // Max Size
        settingService.getSetting(Props.Settings.ThumbCache.maxSizeMb).let { result ->
            State.ThumbCache.maxSizeMb = result.valueOrNull?.value?.toIntOrNull()
        }

        // Folder path
        settingService.getSetting(Props.Settings.ThumbCache.maxAge).let { result ->
            State.ThumbCache.maxAge = result.valueOrNull?.value?.toLongOrNull()
        }

        val c = State.ThumbCache
        if (c.isEnabled) {
            println("Thumbnail file caching is enabled.")
            c.maxSizeMb?.let { println("Max size: $it MB") }
            c.maxAge?.let { println("Max age: ${formatSecondsToReadableTime(it)}") }
            c.folderPath?.let { println(it) }
            println("---")
        } else {
            println("Thumbnail file caching is disabled.")
        }
    }

    fun set_thumbnail(
        user: Principal,
        isEnabled: Boolean?,
        deleteCache: Boolean?,
        moveCache: Boolean?,
        folderPath: FilePath?,
        maxSizeMb: Int?,
        maxAge: Long?,
    ): Result<FilePath?> {
        val previousFolderPath = State.ThumbCache.folderPath?.let { FilePath.of(it) }
        val log = logChange(userId = user.userId)

        try {
            if (isEnabled != null) {
                settingService.db_setSetting(Props.Settings.ThumbCache.enabled, isEnabled.toString()).let {
                    if (it.isNotSuccessful) return it.cast()
                }
                log.add(Props.Settings.ThumbCache.enabled, isEnabled)

                State.ThumbCache.isEnabled = isEnabled
            }

            if (folderPath != null) {
                State.ThumbCache.folderPath = folderPath.pathString
                settingService.db_setSetting(Props.Settings.ThumbCache.folderPath, folderPath.pathString).let {
                    if (it.isNotSuccessful) return it.cast()
                }
                log.add(Props.Settings.ThumbCache.folderPath, folderPath)

                // Move existing cache files
                val folderPathChanged = folderPath != previousFolderPath
                val shouldMoveCache = moveCache == true &&
                        folderPathChanged &&
                        previousFolderPath != null &&
                        previousFolderPath.exists()

                if (shouldMoveCache) {
                    filesystemService.moveFile(
                        source = previousFolderPath!!,
                        destination = folderPath,
                        user = user,
                        ignorePermissions = true,
                    ).let {
                        if (it.isNotSuccessful) return it.cast()
                    }
                    log.append("Existing cache folder was moved.")
                } else if (folderPathChanged) {
                    filesystemService.createFolder(folderPath).let {
                        if (it.isNotSuccessful) return it.cast()
                    }
                }

                if (deleteCache == true && !shouldMoveCache) {
                    val deleteTarget = if (folderPathChanged) previousFolderPath else folderPath

                    if (deleteTarget != null && deleteTarget.exists()) {
                        filesystemService.deleteFile(
                            target = deleteTarget,
                            user = user,
                            ignorePermissions = true,
                        ).let {
                            if (it.isNotSuccessful) return it.cast()
                        }
                        log.append("Existing cache folder was deleted.")
                    }
                }
            }

            if (deleteCache == true && folderPath == null && previousFolderPath != null && previousFolderPath.exists()) {
                filesystemService.deleteFile(
                    target = previousFolderPath,
                    user = user,
                    ignorePermissions = true,
                ).let {
                    if (it.isNotSuccessful) return it.cast()
                }
                log.append("Existing cache was cleared.")
            }

            if (maxSizeMb != null) {
                settingService.db_setSetting(Props.Settings.ThumbCache.maxSizeMb, maxSizeMb.toString()).let {
                    if (it.isNotSuccessful) return it.cast()
                }
                log.add(Props.Settings.ThumbCache.maxSizeMb, maxSizeMb)

                State.ThumbCache.maxSizeMb = maxSizeMb
            }

            if (maxAge != null) {
                settingService.db_setSetting(Props.Settings.ThumbCache.maxAge, maxAge.toString()).let {
                    if (it.isNotSuccessful) return it.cast()
                }
                log.add(Props.Settings.ThumbCache.maxAge, maxAge)

                State.ThumbCache.maxAge = maxAge
            }

            thumbnailService.triggerActivityCleanup()

            return Result.ok(folderPath)
        } finally {
            log.log()
        }
    }

    private fun logChange(userId: Ulid) = object {
        private var message = "Thumbnail cache settings changed:"
        private var count = 0

        fun log() {
            if (count == 0) return
            logService.info(
                type = LogType.AUDIT,
                action = UserAction.UPDATE_THUMBNAIL_CACHE_SETTING,
                description = "Settings changed for thumbnail cache",
                message = message,
                initiatorId = userId,
            )
        }
        fun add(setting: String, value: Any) {
            message = "$message\n\n$setting was changed to:\n$value"
            count++
        }
        fun append(string: String) {
            message = "$message\n\n$string"
            count++
        }
    }
}
