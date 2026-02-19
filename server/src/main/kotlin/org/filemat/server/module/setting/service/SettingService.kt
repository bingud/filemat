package org.filemat.server.module.setting.service

import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.plural
import org.filemat.server.common.util.resolvePath
import org.filemat.server.common.util.unixNow
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.TusService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.setting.model.Setting
import org.filemat.server.module.setting.repository.SettingRepository
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.withLock

@Service
class SettingService(
    private val settingRepository: SettingRepository,
    private val logService: LogService,
    private val filesystemService: FilesystemService,
    private val tusService: TusService,
) {

    fun set_uploadFolderPath(user: Principal, rawNewPath: FilePath): Result<FilePath> {
        tusService.uploadLock.writeLock().withLock {
            val newPath = resolvePath(rawNewPath, allowNonExistent = true).let { result ->
                if (result.isNotSuccessful) return result.cast()
                result.value
            }
            val oldPath = FilePath.of(State.App.uploadFolderPath)

            filesystemService.isFolderEmpty(newPath.path, ignoreEmpty = true).let {
                if (it.isNotSuccessful) return it.cast()
                if (it.value == false) return Result.reject("Upload folder must be empty.")
            }

            db_setSetting(Props.Settings.uploadFolderPath, newPath.pathString).let {
                if (it.isNotSuccessful) return it.cast()
            }

            State.App.uploadFolderPath = newPath.pathString

            logService.info(
                type = LogType.AUDIT,
                action = UserAction.UPDATE_UPLOAD_FOLDER_PATH,
                description = "Upload folder path was changed.",
                message = "Old path:\n$oldPath\n\nNew path:\n$newPath",
                initiatorId = user.userId,
            )

            val createdTusService = filesystemService.initializeTusService()
            val tusMessage = if (createdTusService) "" else "Failed to start upload service."

            // Move files to new location
            val files = kotlin.runCatching {
                Files.list(oldPath.path).use { it.toList() }
            }.getOrElse { return Result.error("Upload folder was changed. Failed to move existing upload files.") }

            val failedMoves = mutableListOf<Pair<Path, String>>()
            files.forEach { child: Path ->
                filesystemService.moveFile(
                    FilePath.ofAlreadyNormalized(child),
                    FilePath.ofAlreadyNormalized(newPath.path.resolve(child.fileName)),
                    user,
                    ignorePermissions = true
                ).let {
                    if (it.isNotSuccessful) failedMoves.add(child to (it.errorOrNull ?: "No error message."))
                }
            }

            if (failedMoves.size > 0) {
                val str = failedMoves.joinToString("\n\n") { (path, reason) -> "$reason: \n$path" }
                logService.info(
                    type = LogType.SYSTEM,
                    action = UserAction.UPDATE_UPLOAD_FOLDER_PATH,
                    description = "Failed to move upload files to new upload folder..",
                    message = str,
                    initiatorId = user.userId,
                )

                return Result.error("Upload folder was changed. Failed to move ${failedMoves} ${plural("file", failedMoves.size)}. $tusMessage")
            }

            if (tusMessage.isNotBlank()) return Result.error("Upload folder was changed. $tusMessage")
            return Result.ok(newPath)
        }
    }

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
                description = "Failed to save setting in the database. Setting name: [$name]",
                message = e.stackTraceToString()
            )
            Result.error("Failed to save setting in the database.")
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