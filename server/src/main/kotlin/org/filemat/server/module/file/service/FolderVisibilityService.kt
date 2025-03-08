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
    private val hiddenFolderTrie = VisibilityTrie()

    /**
     * Initialize folder visibility service
     */
    fun initialize() {
        println("Loading folder visibility configurations...\n")

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
            println("Folder visibility configurations loaded:\n### EXPOSED FOLDERS:\n${exposedPaths.joinToString("\t")}\n\n### HIDDEN FOLDERS:\n${hiddenPaths.joinToString("\t")}")
        } else {
            println("No folders have been exposed or hidden.")
        }

        // Insert true because VisibilityTrie returns false by default
        State.App.hiddenFolders.forEach { hiddenFolderTrie.insert(it, true) }
    }

    /**
     * returns if a folder path is not blocked
     */
    fun isPathAllowed(folderPath: String, isNormalized: Boolean = false): String? {
        val path = if (isNormalized) folderPath else folderPath.normalizePath()

        if (State.App.hideSensitiveFolders && Props.sensitiveFolders.contains(path, isPathNormalized = true)) {
            return "This file is blocked because it is marked as sensitive."
        }

        val isForceHidden = hiddenFolderTrie.getVisibility(path)
        // isExposed is set to true because all folders in this list are forcefully hidden
        if (isForceHidden.isExposed == true) return "This file is blocked."

        val visibility = visibilityTrie.getVisibility(path)
        return if (visibility.isExposed) null else "This file is not exposed."
    }

    /**
     * Create new folder visibility configurations
     */
    fun insertPaths(paths: List<IFolderVisibility>, userAction: UserAction): Result<Unit> {
        try {
            val now = unixNow()
            paths.forEach {
                folderVisibilityRepository.insertOrReplace(it.path, it.isExposed, now)
                visibilityTrie.insert(it.path, it.isExposed)
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