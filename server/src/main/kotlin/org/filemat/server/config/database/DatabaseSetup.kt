package org.filemat.server.config.database

import org.filemat.server.common.State
import org.filemat.server.config.Props
import org.filemat.server.config.platform.AclPolicy
import org.filemat.server.config.platform.Platform
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.role.service.RoleService
import org.filemat.server.module.service.AppService
import org.filemat.server.module.setting.service.SettingService
import org.filemat.server.module.setting.service.component.ThumbCacheSettingService
import org.flywaydb.core.Flyway
import org.springframework.core.env.Environment
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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
    private val environment: Environment,
    private val thumbCacheSettingService: ThumbCacheSettingService,
    private val state: State,
) {
    private val keptBackups = 3

    fun runFlywayMigrations() {
        println("Running flyway migrations...")

        val jdbcUrl = environment.getProperty("spring.datasource.url")
            ?: return println("JDBC URL is null.")

        val flyway = Flyway.configure()
            .dataSource(jdbcUrl, /* user */ null,  /* Password */ null)
            .baselineOnMigrate(true)
            .load()

        backUpBeforeMigrations(flyway)
        val result = flyway.migrate()
        println("Flyway migrations applied: ${result.migrationsExecuted}")
    }

    /**
     * Copies the database next to itself before pending migrations change it. A new database has nothing to back up.
     */
    private fun backUpBeforeMigrations(flyway: Flyway) {
        val info = flyway.info()
        if (info.pending().isEmpty()) return
        val version = info.current()?.version ?: return
        writeBackup(Props.databaseFile, version.toString())
    }

    /**
     * Writes a copy of [databaseFile], as private as the database itself (it holds password hashes and sessions),
     * and keeps only the newest [keptBackups] copies.
     */
    internal fun writeBackup(databaseFile: Path, version: String): Path {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val backup = databaseFile.resolveSibling("${databaseFile.fileName}.v$version-$timestamp.bak")
        println("Backing up the database to $backup before migrating...")
        // Created empty and private first, so the copy is never readable by others. VACUUM INTO fills an empty file.
        AclPolicy.createPrivateFile(backup, "rw-------")
        // VACUUM INTO writes a consistent copy while the database is open
        jdbcTemplate.update("VACUUM INTO ?", backup.toString())

        val backups = Files.newDirectoryStream(databaseFile.parent, "${databaseFile.fileName}.v*.bak").use { it.toList() }
        backups.sortedByDescending { Files.getLastModifiedTime(it) }.drop(keptBackups).forEach { runCatching { Files.delete(it) } }
        return backup
    }

    /**
     * Windows: recomputes entity path keys, because folders can be made case-sensitive while the server is stopped.
     * V9 also builds them once when it adds the column, so on the first start after that migration this finds nothing to change.
     */
    fun initialize_rebuildPathKeys() {
        if (!Platform.isWindows) return
        val changed = jdbcTemplate.execute(ConnectionCallback { PathKeyRebuild.run(it) }) ?: 0
        if (changed > 0) println("Updated the path keys of $changed indexed files.")
    }

    /**
     * Gives entities that have no file key yet the key of their file, when the file exists.
     * They are normally repaired when opened; this also covers files that are never opened, such as files that came back
     * while the server was stopped. Entities whose file is still missing wait for the next start.
     */
    fun initialize_repairFileKeys() {
        val changed = jdbcTemplate.execute(ConnectionCallback { FileKeyBackfill.run(it, onlySupported = true) }) ?: 0
        if (changed > 0) println("Added file keys to $changed indexed files.")
    }

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
                state.app.followSymlinks = bool ?: false
            }
        }

        // Upload folder path setting. Parsed like any path, so `D:/` keeps its slash: `D:` alone means D's current folder.
        settingService.getSetting(Props.Settings.uploadFolderPath).let { result ->
            state.app.uploadFolderPath = result.valueOrNull?.value?.let { runCatching { FilePath.of(it).pathString }.getOrNull() }
                ?: Props.defaultUploadFolderPath
        }

        settingService.getSetting(Props.Settings.contentBaseUrl).let { result ->
            result.valueOrNull?.value?.let { value ->
                state.app.contentBaseUrl.url = value
            }
        }

        settingService.getSetting(Props.Settings.contentBaseUrlForUnauthenticated).let { result ->
            result.valueOrNull?.value?.toBooleanStrictOrNull()?.let { enabled ->
                state.app.contentBaseUrl.forUnauthenticated = enabled
            }
        }

        // Is app setup setting
        settingService.getSetting(Props.Settings.isAppSetup).let { result ->
            result.valueOrNull?.value?.toBooleanStrictOrNull().let { bool ->
                state.app.isSetup = bool ?: false
                if (bool != true) {
                    appService.generateSetupCode()
                }
            }
        }

        // Thumbnail cache settings
        thumbCacheSettingService.initializeSettings()
    }
}