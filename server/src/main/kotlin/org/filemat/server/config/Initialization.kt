package org.filemat.server.config

import kotlinx.coroutines.*
import org.filemat.server.common.State
import org.filemat.server.config.database.DatabaseSetup
import org.filemat.server.module.file.service.FolderVisibilityService
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class Initialization(
    private val databaseSetup: DatabaseSetup,
    private val folderVisibilityService: FolderVisibilityService,
) {

    /**
     * #### Startup initialization
     */
    @EventListener(ApplicationReadyEvent::class)
    fun initialize() = CoroutineScope(Dispatchers.Default).launch {
        databaseSetup.initialize_setUpSchema().line()
        databaseSetup.initialize_systemRoles().line()
        databaseSetup.initialize_loadRolesToMemory().line()

        if (State.App.isSetup) {
            folderVisibilityService.initialize().line()
        }

        databaseSetup.initialize_loadSettings().line()

        State.App.isInitialized = true
    }

    private fun <T> T.line() = this.also { println() }

}