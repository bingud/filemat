package org.filemat.server.module.savedFile

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.resolvePath
import org.filemat.server.common.util.unixNow
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FullFileMetadata
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.sharedFile.repository.SavedFileRepository
import org.filemat.server.module.user.model.UserAction
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class SavedFileService(
    private val savedFileRepository: SavedFileRepository,
    private val logService: LogService,
    @Lazy private val fileService: FileService
) {
    private val fileMap = ConcurrentHashMap<Ulid, ConcurrentHashMap<String, SavedFile>>()
    private val pathToUserIdMap = ConcurrentHashMap<String, MutableSet<Ulid>>()
    private var useMap = true

    fun initialize_loadSavedFilesFromDatabase() {
        try {
            println("Loading saved files from database...")
            savedFileRepository.findAll()
                .forEach {
                    addToMap(it)
                }
        } catch (e: Exception) {
            useMap = false
            fileMap.clear()
            pathToUserIdMap.clear()

            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.NONE,
                description = "Failed to load saved files from database during startup.",
                message = e.stackTraceToString(),
            )
        }
    }

    // Map helper functions
    private fun addToMap(file: SavedFile) {
        if (!useMap) return

        val map = fileMap.getOrPut(file.userId) { ConcurrentHashMap() }
        map.put(file.path, file)

        addToReverseMap(file.path, file.userId)
    }

    private fun addToReverseMap(path: String, userId: Ulid) {
        val usersWithPath = pathToUserIdMap.getOrPut(path) { ConcurrentHashMap.newKeySet() }
        usersWithPath.add(userId)
    }

    private fun removeFromMap(path: String, userId: Ulid?) {
        if (!useMap) return

        if (userId != null) {
            fileMap.get(userId)?.let { map ->
                map.remove(path)
                if (map.isEmpty()) {
                    fileMap.remove(userId)
                }
            }
            removeFromReverseMap(path, userId)
        } else {
            val userIds = pathToUserIdMap.get(path) ?: return
            pathToUserIdMap.remove(path)

            userIds.forEach { id ->
                fileMap.get(id)?.remove(path)
            }
        }
    }

    private fun removeFromReverseMap(path: String, userId: Ulid) {
        pathToUserIdMap.get(path)?.let {
            it.remove(userId)
            if (it.isEmpty()) {
                pathToUserIdMap.remove(path)
            }
        }
    }

    fun changePathInMap(oldPath: String, newPath: String) {
        if (!useMap) return

        // Identify all keys that are either the exact path or a child of that path
        val affectedPaths = pathToUserIdMap.keys.filter {
            it == oldPath || it.startsWith("$oldPath/")
        }

        affectedPaths.forEach { currentPath ->
            // Calculate the new path for this specific entry
            val targetPath = if (currentPath == oldPath) {
                newPath
            } else {
                newPath + currentPath.substring(oldPath.length)
            }

            // Move users from old key to new key in reverse map
            val userIdsToMove = pathToUserIdMap.remove(currentPath) ?: return@forEach

            val targetSet = pathToUserIdMap.getOrPut(targetPath) {
                ConcurrentHashMap.newKeySet()
            }
            targetSet.addAll(userIdsToMove)

            // Update the file objects in the main map for each user
            userIdsToMove.forEach { userId ->
                fileMap[userId]?.let { userFiles ->
                    val file = userFiles.remove(currentPath) ?: return@let
                    userFiles[targetPath] = file.copy(path = targetPath)
                }
            }
        }
    }

    fun changePath(path: FilePath, newPath: FilePath): Result<Unit> {
        try {
            savedFileRepository.updatePath(path.pathString, newPath.pathString)
            changePathInMap(path.pathString, newPath.pathString)

            return Result.ok()
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = UserAction.UPDATE_SAVED_FILE,
                description = "Failed to change path of saved files.",
                message = e.stackTraceToString(),
            )
            return Result.error("Failed to update paths of saved files.")
        }
    }

    fun getAll(user: Principal): Result<List<FullFileMetadata>> {
        val entries =
            if (useMap) {
                fileMap.get(user.userId)?.values ?: return Result.ok(emptyList())
            } else {
                getAllFromDatabase(user.userId).let {
                    if (it.isNotSuccessful) return it.cast()
                    it.value
                }
            }

        val metaList = entries.mapNotNull {
            val rawPath = FilePath.of(it.path)

            val pathResult = resolvePath(rawPath)
            if (pathResult.isNotSuccessful) return@mapNotNull null
            val canonicalPath = pathResult.value

            fileService.getFullMetadata(
                user = user,
                rawPath = rawPath,
                canonicalPath = canonicalPath,
            ).let { result ->
                if (result.isNotSuccessful) return@mapNotNull null
                return@mapNotNull result.value
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

    fun addSavedFile(user: Principal, path: FilePath): Result<SavedFile> {
        val file = SavedFile(user.userId, path.pathString, unixNow())

        exists(user.userId, path.pathString).let {
            if (it.isNotSuccessful) return it.cast()
            if (it.value == true) return file.toResult()
        }

        try {
            savedFileRepository.create(
                userId = file.userId,
                path = file.path,
                createdDate = file.createdDate
            )
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

        addToMap(file)

        return Result.ok(file)
    }

    fun removeSavedFile(path: FilePath, user: Principal? = null): Result<Unit> {
        remove(path.pathString, user?.userId).let {
            if (it.isNotSuccessful) return it.cast()
        }

        removeFromMap(path.pathString, user?.userId)

        return Result.ok()
    }

    private fun remove(path: String, userId: Ulid?): Result<Unit> {
        return try {
            if (userId != null) {
                savedFileRepository.removeByUserId(userId, path)
            } else {
                savedFileRepository.remove(path)
            }
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