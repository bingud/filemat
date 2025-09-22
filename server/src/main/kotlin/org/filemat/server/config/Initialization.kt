package org.filemat.server.config

import jakarta.annotation.PostConstruct
import org.filemat.server.common.State
import org.filemat.server.config.database.DatabaseSetup
import org.filemat.server.module.file.service.FilesystemService
import org.filemat.server.module.file.service.FileVisibilityService
import org.filemat.server.module.permission.service.EntityPermissionService
import org.springframework.stereotype.Component
import kotlin.system.exitProcess

@Component("initialization")
class Initialization(
    private val databaseSetup: DatabaseSetup,
    private val fileVisibilityService: FileVisibilityService,
    private val filePermissionService: EntityPermissionService,
    private val filesystemService: FilesystemService,
) {

    /**
     * #### Initializes the application.
     */
    @PostConstruct //(ApplicationReadyEvent::class)
    fun initialize() {
        // Initialize singletons
        State
        Props

        databaseSetup.initialize_setUpSchema().line()
        databaseSetup.initialize_systemRoles().line()
        databaseSetup.initialize_loadRolesToMemory().line()

        Props.sensitiveFolders.printSensitiveFolders()

        // Load settings to memory
        databaseSetup.initialize_loadSettings().line()

        if (State.App.isSetup) {
            // Load exposed folders to memory
            fileVisibilityService.initialize().line()

            // Load file permissions to memory
            filePermissionService.loadPermissionsFromDatabase()
                .also { if (!it) shutdown(1) }.line()

            filesystemService.initializeTusService()
        }

        println("${Props.appName} is initialized.")
        State.App.isInitialized = true
    }

    // Prints new line
    private fun <T> T.line() = this.also { println() }
    private fun shutdown(code: Int): Nothing { Thread.sleep(200); exitProcess(code) }

}