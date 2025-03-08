package org.filemat.server.config

import kotlinx.coroutines.*
import org.filemat.server.common.State
import org.filemat.server.config.database.DatabaseSetup
import org.filemat.server.module.file.service.FolderVisibilityService
import org.filemat.server.module.permission.service.EntityPermissionService
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import kotlin.system.exitProcess

@Component
class Initialization(
    private val databaseSetup: DatabaseSetup,
    private val folderVisibilityService: FolderVisibilityService,
    private val filePermissionService: EntityPermissionService,
) {

    /**
     * #### Startup initialization
     */
    @EventListener(ApplicationReadyEvent::class)
    fun initialize() = CoroutineScope(Dispatchers.Default).launch {
        databaseSetup.initialize_setUpSchema().line()
        databaseSetup.initialize_systemRoles().line()
        databaseSetup.initialize_loadRolesToMemory().line()

        Props.sensitiveFolders.printSensitiveFolders()

        // Load settings to memory
        databaseSetup.initialize_loadSettings().line()

        if (State.App.isSetup) {
            // Load exposed folders to memory
            folderVisibilityService.initialize().line()

            // Load file permissions to memory
            filePermissionService.loadPermissionsFromDatabase()
                .also { if (!it) shutdown(1) }.line()


        }

        println("${Props.appName} is initialized.")
        State.App.isInitialized = true
    }

    // Prints new line
    private fun <T> T.line() = this.also { println() }
    private fun shutdown(code: Int): Nothing { Thread.sleep(200); exitProcess(code) }

}