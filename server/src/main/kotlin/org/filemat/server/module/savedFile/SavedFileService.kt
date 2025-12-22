package org.filemat.server.module.savedFile

import com.github.f4b6a3.ulid.Ulid
import jakarta.annotation.PostConstruct /*
import mrbean                           */
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.resolvePath
import org.filemat.server.common.util.unixNow
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FullFileMetadata
import org.filemat.server.module.file.service.FileService
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.sharedFile.repository.SavedFileRepository
import org.filemat.server.module.user.model.UserAction
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.ConcurrentHashMap

@Service
class SavedFileService(
    private val savedFileRepository: SavedFileRepository,
    private val logService: LogService,
    @Lazy private val fileService: FileService
) {
    private val fileMap = ConcurrentHashMap<Ulid, ConcurrentHashMap<String, SavedFile>>()
    private var useMap = true

    @PostConstruct
    private fun loadSavedFilesFromDatabase(){
        try {
            savedFileRepository.findAll()
                .forEach {
                    val map = fileMap.getOrPut(it.userId) { ConcurrentHashMap() }
                    map.put(it.path, it)
                }
        } catch (e: Exception) {
            useMap = false
            fileMap.clear()

            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.NONE,
                description = "Failed to load saved files from database during startup.",
                message = e.stackTraceToString(),
            )
        }
    }

    fun getAll(user: Principal): Result<List<FullFileMetadata>> {
        val entries =
            if (useMap) {
                fileMap.get(user.userId)?.values ?: return Result.notFound()
            } else {
                getAllFromDatabase(user.userId).let {
                    if (it.isNotSuccessful) return it.cast()
                    it.value
                }
            }

        val metaList = entries.mapNotNull {
            val rawPath = FilePath.of(it.path)

            val (pathResult, pathHasSymlink) = resolvePath(rawPath)
            if (pathResult.isNotSuccessful) return pathResult.cast()
            val canonicalPath = pathResult.value

            fileService.getFullMetadata(
                user = user,
                rawPath = rawPath,
                canonicalPath = canonicalPath,
                action = UserAction.GET_SAVED_FILE_LIST
            ).let {
                if (it.isNotSuccessful) return@mapNotNull null
                return@mapNotNull it.value
            }
        }

        return metaList.toResult()
    }

    private fun getAllFromDatabase(userId: Ulid): Result<Collection<SavedFile>> {
        try {
            return savedFileRepository.getAll(userId).toResult()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.GET_SAVED_FILE_LIST,
                description = "Failed to get list of saved files from database.",
                message = e.stackTraceToString(),
                targetId = userId,
            )
            return Result.error("Failed to get list of saved files.")
        }
    }

    @Transactional
    fun addSavedFile(user: Principal, path: FilePath): Result<SavedFile> {
        val file = SavedFile(user.userId, path.pathString, unixNow())

        exists(user.userId, path.pathString).let {
            if (it.isNotSuccessful) return it.cast()
            if (it.value == true) return file.toResult()
        }

        try {
            savedFileRepository.create(userId = file.userId, path = file.path, createdDate = file.createdDate)
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.CREATE_SAVED_FILE,
                description = "Failed to create a saved file in the database",
                message = e.stackTraceToString(),
                initiatorId = user.userId
            )
            return Result.error("Failed to save file.")
        }

        val map = fileMap.getOrPut(user.userId) { ConcurrentHashMap() }
        map.put(file.path, file)

        return Result.ok(file)
    }

    fun removeSavedFile(user: Principal, path: FilePath): Result<Unit> {
        remove(user.userId, path.pathString).let {
            if (it.isNotSuccessful) return it.cast()
        }

        if (useMap) {
            fileMap.get(user.userId)?.let { map ->
                map.remove(path.pathString)
                if (map.isEmpty()) {
                    fileMap.remove(user.userId)
                }
            }
        }

        return Result.ok()
    }

    private fun remove(userId: Ulid, path: String): Result<Unit> {
        return try {
            savedFileRepository.remove(userId, path)
            return Result.ok()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.REMOVE_SAVED_FILE,
                description = "Failed to remove saved file from database.",
                message = e.stackTraceToString(),
                targetId = userId,
            )
            Result.error("Failed to unsave file.")
        }
    }

    fun exists(userId: Ulid, path: String): Result<Boolean> {
        if (useMap) {
            return Result.ok(
                fileMap.get(userId)?.containsKey(path) == true
            )
        }

        return try {
            savedFileRepository.exists(userId, path).toResult()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.GET_SAVED_FILE,
                description = "Failed to check if file path is saved already.",
                message = e.stackTraceToString(),
                targetId = userId,
            )
            Result.error("Failed to check if file path is saved already.")
        }
    }

    fun isSaved(userId: Ulid, path: String) = exists(userId, path).valueOrNull == true
}