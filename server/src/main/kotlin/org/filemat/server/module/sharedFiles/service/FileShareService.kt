package org.filemat.server.module.sharedFiles.service

import com.github.f4b6a3.ulid.Ulid
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.dto.ArgonHash
import org.filemat.server.common.util.resolvePath
import org.filemat.server.common.util.unixNow
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.auth.model.Principal.Companion.hasPermission
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.file.service.FileService
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.sharedFiles.model.FileShare
import org.filemat.server.module.sharedFiles.model.isExpired
import org.filemat.server.module.sharedFiles.repository.FileShareRepository
import org.filemat.server.module.user.model.UserAction
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service

@Service
class FileShareService(
    private val fileShareRepository: FileShareRepository,
    private val logService: LogService,
    private val entityService: EntityService,
    @Lazy private val fileService: FileService,
) {
    private final val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @PostConstruct
    fun clearExpiredFileShares() {
        scope.launch {
            var errorLogged = false

            while (true) {
                delay(300_000) // 5 Minutes

                try {
                    fileShareRepository.deleteExpired(unixNow())
                    errorLogged = false
                } catch (e: Exception) {
                    if (!errorLogged) {
                        errorLogged = true
                        logService.error(
                            type = LogType.SYSTEM,
                            action = UserAction.CLEAR_EXPIRED_FILE_SHARES,
                            description = "Failed to clear expired file shares.",
                            message = e.stackTraceToString(),
                        )
                    }
                }
            }
        }
    }

    @PreDestroy
    fun stop() {
        scope.cancel()
    }

    fun deleteShare(
        principal: Principal,
        entityId: Ulid,
        shareId: String,
        userAction: UserAction,
    ): Result<Unit> {
        val entity = entityService.getById(entityId, userAction)
            .let {
                if (it.isNotSuccessful) return it.cast()
                it.value
            }

        val share = getShareByShareIdAndEntityId(shareId, entityId, userAction)
            .let {
                if (it.isNotSuccessful) return it.cast()
                it.value
            }

        fileService.isAllowedToShareFile(principal, FilePath.of(entity.path!!))
            .let {
                if (it.isNotSuccessful) return it.cast()
            }

        val canManageAll = principal.hasPermission(SystemPermission.MANAGE_ALL_FILE_SHARES)

        if (canManageAll == false && share.userId != principal.userId) {
            return Result.reject("You do not have permission to delete this share.")
        }

        return db_deleteShare(shareId, entityId, userAction)
    }

    private fun db_deleteShare(shareId: String, entityId: Ulid, userAction: UserAction): Result<Unit> {
        try {
            fileShareRepository.delete(shareId, entityId)
            return Result.ok()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction,
                description = "Failed to delete file share from the database.",
                message = e.stackTraceToString(),
            )
            return Result.error("Failed to delete file share.")
        }
    }

    fun createShare(
        principal: Principal,
        rawPath: FilePath,
        sharePath: String,
        password: ArgonHash?,
        maxAge: Long?
    ): Result<FileShare> {
        val (canonicalResult, pathHasSymlink) = resolvePath(rawPath)
        val canonicalPath = canonicalResult.let {
            if (it.isNotSuccessful) return canonicalResult.cast()
            it.value
        }

        // Check permissions to read and share file
        fileService.isAllowedToShareFile(principal, canonicalPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        // Check if share ID is taken
        getSharesByShareId(sharePath, UserAction.SHARE_FILE)
            .let {
                if (it.hasError) return Result.error("Failed to check if share link is already used.")

                if (it.valueOrNull?.isExpired() == true) return@let
                if (it.valueOrNull != null) return Result.reject("This share link is already used.")
            }

        // Get entity
        val existingEntity = entityService.getByPath(canonicalPath.pathString, UserAction.SHARE_FILE)
            .let {
                if (!it.notFound && it.isNotSuccessful) return it.cast()
                if (it.isSuccessful) return@let it.value
                null
            }
        val entity = existingEntity ?: entityService.create(canonicalPath, null, UserAction.SHARE_FILE, State.App.followSymlinks)
            .let {
                if (it.isNotSuccessful) return it.cast()
                it.value
            }

        val share = FileShare(
            shareId = sharePath,
            fileId = entity.entityId,
            userId = principal.userId,
            createdDate = unixNow(),
            maxAge = maxAge ?: 0,
            isPassword = password != null
        )

        insertFileShare(share, UserAction.SHARE_FILE).let {
            if (it.isNotSuccessful) return it.cast()
        }

        return share.toResult()
    }

    private fun insertFileShare(share: FileShare, userAction: UserAction): Result<Unit> {
        try {
            fileShareRepository.insert(
                shareId = share.shareId,
                fileId = share.fileId,
                userId = share.userId,
                createdDate = share.createdDate,
                maxAge = share.maxAge,
                isPassword = share.isPassword,
                password = share.password
            )
            return Result.ok()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction,
                description = "Failed to insert file share into database.",
                message = e.stackTraceToString(),
            )
            return Result.error("Failed to save file share.")
        }
    }

    fun getSharesByPath(principal: Principal, rawPath: FilePath): Result<List<FileShare>> {
        val (canonicalResult, pathHasSymlink) = resolvePath(rawPath)
        val canonicalPath = canonicalResult.let {
            if (it.isNotSuccessful) return canonicalResult.cast()
            it.value
        }

        // Check READ permission for file
        fileService.isAllowedToAccessFile(principal, canonicalPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        val canManageAll = principal.hasPermission(SystemPermission.MANAGE_ALL_FILE_SHARES)

        val entityId = entityService.getEntityIdByPath(rawPath, UserAction.GET_FILE_SHARES).let {
            if (it.notFound) return Result.ok(emptyList())
            if (it.isNotSuccessful) return it.cast()
            it.value
        }

        val allShares = getSharesByEntityId(entityId, UserAction.GET_FILE_SHARES).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }

        // Get user's own shares
        val userShares = if (canManageAll) allShares else allShares.filter { it.userId == principal.userId }
        return userShares.toResult()
    }

    fun getSharesByEntityId(entityId: Ulid, userAction: UserAction): Result<List<FileShare>> {
        try {
            val all = fileShareRepository.getSharesByFileId(entityId)
            val valid = all.filter { !it.isExpired() }
            return valid.toResult()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction ,
                description = "Failed to get file shares from the database by file ID.",
                message = e.stackTraceToString(),
            )
            return Result.error("Failed to get file shares.")
        }
    }

    fun getSharesByShareId(shareId: String, userAction: UserAction): Result<FileShare> {
        try {
            val result = fileShareRepository.getSharesByShareId(shareId)
                ?: return Result.notFound()
            if (result.isExpired()) return Result.notFound()
            return result.toResult()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction ,
                description = "Failed to get file share from the database by share ID.",
                message = e.stackTraceToString(),
            )
            return Result.error("Failed to get file shares.")
        }
    }

    fun getShareByShareIdAndEntityId(shareId: String, entityId: Ulid, userAction: UserAction): Result<FileShare> {
        try {
            val result = fileShareRepository.getShareByShareIdAndFileId(shareId, entityId)
                ?: return Result.notFound()

            return result.toResult()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction ,
                description = "Failed to get file share from the database.",
                message = e.stackTraceToString(),
            )
            return Result.error("Failed to get file share.")
        }
    }
}