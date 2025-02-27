package org.filemat.server.module.file.service

import org.filemat.server.common.State
import org.filemat.server.common.util.normalizePath
import org.filemat.server.config.Props
import org.filemat.server.module.file.model.VisibilityTrie
import org.filemat.server.module.file.repository.FolderVisibilityRepository
import org.springframework.stereotype.Service
import kotlin.system.exitProcess

@Service
class FolderVisibilityService(
    private val folderVisibilityRepository: FolderVisibilityRepository,
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

        if (State.App.hideSensitiveFolders && Props.sensitiveFolders.contains(path, isPathNormalized = true)) {
            return false
        }

        val isExposed = visibilityTrie.getVisibility(path)
        return isExposed
    }
}