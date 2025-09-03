package org.filemat.server.module.file.service

import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.unixNow
import org.filemat.server.config.Props
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.IFileVisibility
import org.filemat.server.module.file.model.VisibilityTrie
import org.filemat.server.module.file.repository.FileVisibilityRepository
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service
import kotlin.system.exitProcess

@Service
class FileVisibilityService(
    private val fileVisibilityRepository: FileVisibilityRepository,
    private val logService: LogService,
) {
    private val visibilityTrie = VisibilityTrie()
    private val hiddenFileTrie = VisibilityTrie()

    fun getAllFileVisibilities() = visibilityTrie.getAllVisibilities()

    /**
     * Initialize folder visibility service
     */
    fun initialize() {
        println("Loading file visibility configurations...\n")

        // Hide authentication code file
        hiddenFileTrie.insert(Props.authCodeFile, true)

        val visibilityData = runCatching { fileVisibilityRepository.getAll() }
            .onFailure {
                it.printStackTrace()
                println("FAILED TO LOAD FILE VISIBILITY DATA FROM DATABASE.")
                exitProcess(1)
            }.getOrNull() ?: emptyList()

        visibilityData.forEach { visibilityTrie.insert(it.path, it.isExposed) }

        if (visibilityData.isNotEmpty()) {
            val exposedPaths = visibilityData.filter { it.isExposed }.map { it.path }
            val hiddenPaths = visibilityData.filterNot { it.isExposed }.map { it.path }
            println("File visibility configurations loaded:\n### EXPOSED FILES:\n${exposedPaths.joinToString("\t")}\n\n### HIDDEN FILES:\n${hiddenPaths.joinToString("\t")}")
        } else {
            println("No files have been exposed or hidden.")
        }

        // Insert true because VisibilityTrie returns false by default
        State.App.hiddenFolders.forEach { hiddenFileTrie.insert(it, true) }
    }

    /**
     * returns if a folder path is not blocked
     */
    fun isPathAllowed(canonicalPath: FilePath): String? {
        if (State.App.hideSensitiveFolders && Props.sensitiveFolders.contains(canonicalPath.pathString, isPathNormalized = true)) {
            return "This file is blocked because it is marked as sensitive."
        }

        val isForceHidden = hiddenFileTrie.getVisibility(canonicalPath.pathString)
        // isExposed is set to true because all folders in this list are forcefully hidden
        if (isForceHidden.isExposed == true) return "This file is blocked."

        val visibility = visibilityTrie.getVisibility(canonicalPath.pathString)
        return if (visibility.isExposed) null else "This file is not exposed."
    }

    /**
     * Create new folder visibility configurations
     */
    fun insertPaths(paths: List<IFileVisibility>, userAction: UserAction): Result<Unit> {
        try {
            // Check if any path already exists
            paths.forEach {
                val hasRule = visibilityTrie.hasExplicitRule(it.path)
                if (hasRule) return Result.reject("Added path already exists.")
            }

            val now = unixNow()
            paths.forEach {
                fileVisibilityRepository.insertOrReplace(it.path, it.isExposed, now)
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

    /**
     * Create new folder visibility configurations
     */
    fun insertPath(path: IFileVisibility, userAction: UserAction): Result<Unit> = insertPaths(listOf(path), userAction)
}