package org.filemat.server.config.database

import org.filemat.server.common.State
import org.filemat.server.common.util.addPrefixIfNotPresent
import org.filemat.server.config.Props
import org.filemat.server.module.role.service.RoleService
import org.filemat.server.module.service.AppService
import org.filemat.server.module.setting.service.SettingService
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import kotlin.system.exitProcess

/**
 * Initializes state from the database.
 *
 * Sets up DB schema, creates default roles and loads state into memory.
 */
@Component
class DatabaseSetup(
    private val jdbcTemplate: JdbcTemplate,
    private val settingService: SettingService,
    private val roleService: RoleService,
    private val appService: AppService,
) {
    fun initialize_loadRolesToMemory() {
        if (!roleService.loadRolesToMemory()) {
            println("Failed to load roles to memory.")
            exitProcess(1)
        }
    }

    fun initialize_systemRoles() {
        val sysRoles = roleService.createSystemRoles()
        if (!sysRoles) {
            println("Failed to create default system user roles.")
            exitProcess(1)
        }
    }

    fun initialize_setUpSchema() {
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
    fun initialize_loadSettings() {
        // Follow symlinks setting
        settingService.getSetting(Props.Settings.followSymlinks).let { result ->
            result.valueOrNull?.value?.toBooleanStrictOrNull().let { bool ->
                State.App.followSymlinks = bool ?: false
            }
        }

        // Upload folder path setting
        settingService.getSetting(Props.Settings.uploadFolderPath).let { result ->
            result.valueOrNull?.value.let { path ->
                State.App.uploadFolderPath = path?.removeSuffix("/")?.addPrefixIfNotPresent('/') ?: Props.defaultUploadFolderPath
            }
        }

        // Is app setup setting
        settingService.getSetting(Props.Settings.isAppSetup).let { result ->
            result.valueOrNull?.value?.toBooleanStrictOrNull().let { bool ->
                State.App.isSetup = bool ?: false
                if (bool != true) {
                    appService.generateSetupCode()
                }
            }
        }
    }
}