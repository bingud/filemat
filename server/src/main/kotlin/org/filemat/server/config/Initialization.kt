package org.filemat.server.config

import jakarta.annotation.PostConstruct
import org.filemat.server.common.State
import org.filemat.server.config.database.DatabaseSetup
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.file.service.FileVisibilityService
import org.filemat.server.module.permission.service.EntityPermissionService
import org.filemat.server.module.savedFile.SavedFileService
import org.springframework.stereotype.Component
import kotlin.system.exitProcess

@Component("initialization")
class Initialization(
    private val databaseSetup: DatabaseSetup,
    private val fileVisibilityService: FileVisibilityService,
    private val filePermissionService: EntityPermissionService,
    private val filesystemService: FilesystemService,
    private val savedFileService: SavedFileService,
) {

    /**
     * #### Initializes the application.
     */
    @PostConstruct
    fun initialize() {
        databaseSetup.initialize_setUpSchema().line()
        databaseSetup.runFlywayMigrations().line()

        databaseSetup.initialize_systemRoles().line()
        databaseSetup.initialize_loadRolesToMemory().line()

        Props.sensitiveFolders.printSensitiveFolders()

        // Load settings to memory
        databaseSetup.initialize_loadSettings().line()

        savedFileService.initialize_loadSavedFilesFromDatabase().line()

        if (State.App.isSetup) {
            // Load exposed folders to memory
            fileVisibilityService.initialize().line()

            // Load file permissions to memory
            filePermissionService.loadPermissionsFromDatabase()
                .also {
                    if (!it) {
                        println("Failed to load permissions.")
                        shutdown(1)
                    }
                }.line()

            filesystemService.initializeTusService()
        }

        println("${Props.appName} is initialized.")
        State.App.isInitialized = true
    }

    // Prints new line
    private fun <T> T.line() = this.also { println() }
    private fun shutdown(code: Int): Nothing { Thread.sleep(200); exitProcess(code) }

}