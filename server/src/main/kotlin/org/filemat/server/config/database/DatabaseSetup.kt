package org.filemat.server.config.database

import org.filemat.server.common.State
import org.filemat.server.config.Props
import org.filemat.server.module.role.service.RoleService
import org.filemat.server.module.service.AppService
import org.filemat.server.module.setting.service.SettingService
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.system.exitProcess

@Configuration
class DatabaseSetup(
    private val jdbcTemplate: JdbcTemplate,
    private val settingService: SettingService,
    private val roleService: RoleService,
    private val appService: AppService,
) {

    @EventListener(ApplicationReadyEvent::class)
    fun initialize() {
        setUpSchema()
        checkSettings()

        val sysRoles = roleService.createSystemRoles()
        if (!sysRoles) {
            println("Failed to create default system user roles.")
            exitProcess(1)
        }

        if (!roleService.loadRolesToMemory()) {
            println("Failed to load roles to memory.")
            exitProcess(1)
        }

        println("Database state initialized.")
    }

    private fun setUpSchema() {
        println("Setting schema from `resources/sqlite-schema.sql`")

        val resource = ClassPathResource("sqlite-schema.sql")
        val sql = resource.inputStream.bufferedReader().use { it.readText() }


        println("******************\nExecuting SQL\nSQLite initialization\n******************\n\n")
        println("PRAGMA foreign_keys;")
        val pragmaResult = jdbcTemplate.queryForObject("PRAGMA foreign_keys;", Int::class.java)
        println("RESULT: $pragmaResult")
        if (pragmaResult != 1) {
            println("SQL INITIALIZATION FAILED\nPragma foreign_keys is not 1.\nForeign keys were not enabled for this SQLite connection.")
            exitProcess(1)
        }

        sql.split(";")  // Split by statement
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { statement ->
                println(statement + "\n")
                jdbcTemplate.execute(statement)
            }

        println("SQLite schema initialized.")
    }


    // Settings
    fun checkSettings() {
        setting_isAppSetup()
        State.App.isInitialized = true
    }

    fun setting_isAppSetup() {
        val result = settingService.getSetting(Props.Settings.isAppSetup)
        if (result.valueOrNull?.value == "true") {
            State.App.isSetup = true
        } else {
            appService.generateSetupCode()
        }
    }
}