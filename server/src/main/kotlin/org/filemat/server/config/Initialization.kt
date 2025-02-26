package org.filemat.server.config

import kotlinx.coroutines.*
import org.filemat.server.config.database.DatabaseSetup
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class Initialization(
    private val databaseSetup: DatabaseSetup,
) {

    /**
     * #### Startup initialization
     */
    @EventListener(ApplicationReadyEvent::class)
    fun initialize() = CoroutineScope(Dispatchers.Default).launch {
        databaseSetup.initialize()

    }

}