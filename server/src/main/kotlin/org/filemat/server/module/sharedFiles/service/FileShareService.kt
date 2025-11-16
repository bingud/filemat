package org.filemat.server.module.sharedFiles.service

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.toResult
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.sharedFiles.model.FileShare
import org.filemat.server.module.sharedFiles.repository.FileShareRepository
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service

@Service
class FileShareService(
    private val fileShareRepository: FileShareRepository,
    private val logService: LogService,
) {

    fun getSharesByFileId(fileId: Ulid, userAction: UserAction): Result<List<FileShare>> {
        try {
            return fileShareRepository.getSharesByFileId(fileId).toResult()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction ,
                description = "Failed to get file shares from the database.",
                message = e.stackTraceToString(),
            )
            return Result.error("Failed to get file shares.")
        }
    }

}