package org.filemat.server.module.file.service

import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.normalizePath
import org.filemat.server.common.util.unixNow
import org.filemat.server.config.Props
import org.filemat.server.module.file.model.IFolderVisibility
import org.filemat.server.module.file.model.VisibilityTrie
import org.filemat.server.module.file.repository.FolderVisibilityRepository
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service
import kotlin.system.exitProcess

@Service
class FolderVisibilityService(
    private val folderVisibilityRepository: FolderVisibilityRepository,
    private val logService: LogService,
) {
    private val visibilityTrie = VisibilityTrie()

    fun initialize() {
        println("Loading folder visibility configurations")

        val visibilityData = runCatching { folderVisibilityRepository.getAll() }
            .onFailure {
                it.printStackTrace()
                println("FAILED TO LOAD FOLDER VISIBILITY DATA FROM DATABASE.")
                exitProcess(1)
            }.getOrNull() ?: emptyList()

        visibilityData.forEach { visibilityTrie.insert(it.path, it.isExposed) }

        if (visibilityData.isNotEmpty()) {
            val exposedPaths = visibilityData.filter { it.isExposed }.map { it.path }
            val hiddenPaths = visibilityData.filterNot { it.isExposed }.map { it.path }
            println("Folder visibility configurations loaded:\n### EXPOSED FOLDERS:\n\n${exposedPaths.joinToString("\t")}\n\n### HIDDEN FOLDERS:\n\n${hiddenPaths.joinToString("\t")}")
        } else {
            println("No folders have been exposed or hidden.")
        }
    }

    fun isPathAllowed(rawPath: String): Boolean {
        val path = normalizePath(rawPath)

        val visibility = visibilityTrie.getVisibility(path)

        // If folder doesnt have explicit rule, then check whether to block sensitive folders
        if (State.App.hideSensitiveFolders && Props.sensitiveFolders.contains(path, isPathNormalized = true)) {
            return false
        }

        return visibility.isExposed
    }

    fun insertPaths(paths: List<IFolderVisibility>, userAction: UserAction): Result<Unit> {
        try {
            val now = unixNow()
            paths.forEach {
                folderVisibilityRepository.insertOrReplace(it.path, it.isExposed, now)
            }
            return Result.ok(Unit)
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction,
                description = "Failed to insert folder visibility to database",
                message = e.stackTraceToString()
            )
            return Result.error("Failed to save folder visibility configuration.")
        }
    }
}