package org.filemat.server.config.database

import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.system.exitProcess


@Configuration
class DatabaseConfig(
    private val jdbcTemplate: JdbcTemplate,
) {


}