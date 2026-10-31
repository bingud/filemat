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
    private val state: State,
) {

    fun initializeSettings() {
        // isEnabled
        settingService.getSetting(Props.Settings.ThumbCache.enabled).let { result ->
            state.thumbCache.isEnabled = result.valueOrNull?.value?.toBooleanStrictOrNull() ?: false
        }

        // Folder path
        settingService.getSetting(Props.Settings.ThumbCache.folderPath).let { result ->
            state.thumbCache.folderPath = result.valueOrNull?.value
        }

        // Max Size
        settingService.getSetting(Props.Settings.ThumbCache.maxSizeMb).let { result ->
            state.thumbCache.maxSizeMb = result.valueOrNull?.value?.toIntOrNull()
        }

        // Folder path
        settingService.getSetting(Props.Settings.ThumbCache.maxAge).let { result ->
            state.thumbCache.maxAge = result.valueOrNull?.value?.toLongOrNull()
        }

        val c = state.thumbCache
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

    /**
     * Saves thumbnail cache settings during first-time setup.
     */
    fun saveForSetup(
        initiatorId: Ulid,
        isEnabled: Boolean,
        folderPath: FilePath?,
        maxSizeMb: Int?,
        maxAge: Long?,
    ): Result<Unit> {
        if (maxSizeMb != null && maxSizeMb < 1) return Result.reject("Max Size parameter is too low.")
        if (maxAge != null && maxAge < 1) return Result.reject("Expiration time is too low.")
        // Stored as its real path, like the upload folder, so a link or an 8.3 name compares the same everywhere
        val folder = folderPath?.let { path ->
            state.realInternalFolder(path).let {
                if (it.isNotSuccessful) return Result.reject("Thumbnail cache folder: ${it.error}")
                FilePath.ofAlreadyNormalized(it.value)
            }
        }
        if (isEnabled && folder == null) {
            return Result.reject("Thumbnail cache folder is required when caching is enabled.")
        }

        settingService.db_setSetting(Props.Settings.ThumbCache.enabled, isEnabled.toString()).let {
            if (it.isNotSuccessful) return it.cast()
        }

        if (folder != null) {
            settingService.db_setSetting(Props.Settings.ThumbCache.folderPath, folder.pathString).let {
                if (it.isNotSuccessful) return it.cast()
            }
            if (isEnabled) {
                filesystemService.createFolder(folder).let {
                    if (it.isSuccessful) return@let

                    logService.error(
                        type = LogType.SYSTEM,
                        action = UserAction.APP_SETUP,
                        description = "Failed to create thumbnail cache folder during setup. Path: [$folder]",
                        message = it.exception?.stackTraceToString() ?: (it.errorOrNull ?: ""),
                        initiatorId = initiatorId,
                    )

                    return Result.error("Failed to create thumbnail cache folder.")
                }
            }
        }

        if (maxSizeMb != null) {
            settingService.db_setSetting(Props.Settings.ThumbCache.maxSizeMb, maxSizeMb.toString()).let {
                if (it.isNotSuccessful) return it.cast()
            }
        }

        if (maxAge != null) {
            settingService.db_setSetting(Props.Settings.ThumbCache.maxAge, maxAge.toString()).let {
                if (it.isNotSuccessful) return it.cast()
            }
        }

        logService.info(
            type = LogType.AUDIT,
            action = UserAction.APP_SETUP,
            description = "Thumbnail cache settings saved during application setup.",
            message = buildString {
                append("Enabled: $isEnabled")
                folder?.let { append("\nFolder: $it") }
                maxSizeMb?.let { append("\nMax size: $it MB") }
                maxAge?.let { append("\nMax age: ${formatSecondsToReadableTime(it)}") }
            },
            initiatorId = initiatorId,
        )

        state.thumbCache.isEnabled = isEnabled
        state.thumbCache.folderPath = folder?.pathString
        state.thumbCache.maxSizeMb = maxSizeMb
        state.thumbCache.maxAge = maxAge

        return Result.ok()
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
        val previousFolderPath = state.thumbCache.folderPath?.let { FilePath.of(it) }
        val log = logChange(userId = user.userId)

        if (maxSizeMb != null && maxSizeMb < 1) return Result.reject("Max Size parameter is too low.")
        if (maxAge != null && maxAge < 1) return Result.reject("Expiration time is too low.")
        // Stored as its real path, like the upload folder, so a link or an 8.3 name compares the same everywhere
        val folder = folderPath?.let { path ->
            state.realInternalFolder(path).let {
                if (it.isNotSuccessful) return Result.reject("Thumbnail cache folder: ${it.error}")
                FilePath.ofAlreadyNormalized(it.value)
            }
        }

        try {
            if (isEnabled != null) {
                settingService.db_setSetting(Props.Settings.ThumbCache.enabled, isEnabled.toString()).let {
                    if (it.isNotSuccessful) return it.cast()
                }
                log.add(Props.Settings.ThumbCache.enabled, isEnabled)

                state.thumbCache.isEnabled = isEnabled
            }

            if (folder != null) {
                state.thumbCache.folderPath = folder.pathString
                settingService.db_setSetting(Props.Settings.ThumbCache.folderPath, folder.pathString).let {
                    if (it.isNotSuccessful) return it.cast()
                }
                log.add(Props.Settings.ThumbCache.folderPath, folder)

                // Only the cache files are moved or deleted, so a cache folder that holds other files keeps them.
                // A change of case names the same folder.
                val folderPathChanged = folder.pathKey != previousFolderPath?.pathKey
                val shouldMoveCache = moveCache == true &&
                        folderPathChanged &&
                        previousFolderPath != null &&
                        previousFolderPath.exists()

                if (folderPathChanged) {
                    filesystemService.createFolder(folder).let {
                        if (it.isNotSuccessful) return it.cast()
                    }
                }

                if (shouldMoveCache && previousFolderPath != null) {
                    thumbnailService.moveCache(from = previousFolderPath.path, to = folder.path)
                    log.append("Existing cache files were moved.")
                }

                if (deleteCache == true && !shouldMoveCache) {
                    val deleteTarget = if (folderPathChanged) previousFolderPath else folder

                    if (deleteTarget != null && deleteTarget.exists()) {
                        thumbnailService.clearCache(deleteTarget.path)
                        log.append("Existing cache files were deleted.")
                    }
                }
            }

            if (deleteCache == true && folder == null && previousFolderPath != null && previousFolderPath.exists()) {
                thumbnailService.clearCache(previousFolderPath.path)
                log.append("Existing cache was cleared.")
            }

            if (maxSizeMb != null) {
                settingService.db_setSetting(Props.Settings.ThumbCache.maxSizeMb, maxSizeMb.toString()).let {
                    if (it.isNotSuccessful) return it.cast()
                }
                log.add(Props.Settings.ThumbCache.maxSizeMb, maxSizeMb)

                state.thumbCache.maxSizeMb = maxSizeMb
            }

            if (maxAge != null) {
                settingService.db_setSetting(Props.Settings.ThumbCache.maxAge, maxAge.toString()).let {
                    if (it.isNotSuccessful) return it.cast()
                }
                log.add(Props.Settings.ThumbCache.maxAge, maxAge)

                state.thumbCache.maxAge = maxAge
            }

            thumbnailService.triggerActivityCleanup()

            return Result.ok(folder)
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
