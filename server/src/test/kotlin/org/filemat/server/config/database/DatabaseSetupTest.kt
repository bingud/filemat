package org.filemat.server.config.database

import io.mockk.every
import io.mockk.mockk
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.config.Props
import org.filemat.server.config.platform.FileIdentity
import org.filemat.server.config.platform.PathPolicy
import org.filemat.server.config.platform.Platform
import org.filemat.server.config.platform.WindowsNative
import org.filemat.server.module.setting.model.Setting
import org.filemat.server.module.setting.service.SettingService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.EnumSet

class DatabaseSetupTest {

    @TempDir
    lateinit var tempDir: Path

    private val settingService = mockk<SettingService>(relaxed = true)
    private val state = State(env = emptyMap())
    private val dataSources = mutableListOf<SingleConnectionDataSource>()

    // The temp folder can only be deleted once SQLite has closed its files
    @AfterEach
    fun closeDatabases() = dataSources.forEach { it.destroy() }

    private fun jdbc(database: Path) = JdbcTemplate(SingleConnectionDataSource("jdbc:sqlite:$database", true).also { dataSources += it })

    private fun setupWith(database: Path) = DatabaseSetup(
        jdbcTemplate = jdbc(database),
        settingService = settingService,
        roleService = mockk(relaxed = true),
        appService = mockk(relaxed = true),
        environment = mockk(relaxed = true),
        thumbCacheSettingService = mockk(relaxed = true),
        state = state,
    )

    private fun database(): Path {
        val database = tempDir.resolve("filemat-server.db")
        jdbc(database).execute("CREATE TABLE t (a TEXT)")
        return database
    }

    @Test
    fun `a backup is a full copy and only the newest three are kept`() {
        val database = database()
        val old = (1..3).map { day ->
            Files.writeString(tempDir.resolve("filemat-server.db.v7-2026010$day-000000.bak"), "old")
                .also { Files.setLastModifiedTime(it, FileTime.from(Instant.parse("2026-01-0${day}T00:00:00Z"))) }
        }
        val unrelated = Files.writeString(tempDir.resolve("notes.bak"), "keep")

        val backup = setupWith(database).writeBackup(database, "8")

        assertEquals(0, jdbc(backup).queryForObject("SELECT count(*) FROM t", Int::class.java))
        assertFalse(Files.exists(old[0]), "the oldest backup is removed")
        assertTrue(Files.exists(old[1]) && Files.exists(old[2]))
        assertTrue(Files.exists(unrelated))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a backup is as private as the database`() {
        val database = database()

        val backup = setupWith(database).writeBackup(database, "8")

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(backup)))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a backup on Windows is private to the owners`() {
        // Under a folder that grants Users, the backup would inherit them
        val usersName = WindowsNative.accountNameOfSid("S-1-5-32-545") ?: error("BUILTIN\\Users not found")
        val view = Files.getFileAttributeView(tempDir, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        view.acl = view.acl + AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(tempDir.fileSystem.userPrincipalLookupService.lookupPrincipalByName(usersName))
            .setPermissions(EnumSet.of(AclEntryPermission.READ_DATA))
            .setFlags(AclEntryFlag.FILE_INHERIT)
            .build()
        val database = database()

        val backup = setupWith(database).writeBackup(database, "8")

        val acl = Files.getFileAttributeView(backup, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).acl
        assertTrue(acl.none { it.principal().name == usersName }, acl.toString())
    }

    @Test
    fun `entities without a file key get one at startup when their file exists`() {
        val database = tempDir.resolve("filemat-server.db")
        val db = jdbc(database)
        db.execute("CREATE TABLE files (entity_id TEXT PRIMARY KEY, path TEXT, is_filesystem_supported INTEGER, file_key TEXT)")
        val file = Files.writeString(tempDir.resolve("a.txt"), "a")
        db.update("INSERT INTO files VALUES ('e1', ?, 1, NULL)", PathPolicy.toApiString(file))
        db.update("INSERT INTO files VALUES ('e2', ?, 1, NULL)", PathPolicy.toApiString(tempDir.resolve("missing.txt")))
        // On an unsupported filesystem there is no key to repair
        db.update("INSERT INTO files VALUES ('e3', ?, 0, NULL)", PathPolicy.toApiString(file))

        setupWith(database).initialize_repairFileKeys()

        val keys = db.queryForList("SELECT entity_id, file_key FROM files ORDER BY entity_id").associate { it["entity_id"] to it["file_key"] }
        assertEquals(FileIdentity.fileKey(file), keys["e1"])
        assertNull(keys["e2"])
        assertNull(keys["e3"])
    }

    @Test
    fun `the upload folder setting is read like any path`() {
        val stored = if (Platform.isWindows) "D:/" else "tmp/filemat/"
        every { settingService.getSetting(any()) } returns Result.notFound()
        every { settingService.getSetting(Props.Settings.uploadFolderPath) } returns Result.ok(Setting(Props.Settings.uploadFolderPath, stored, 0))

        setupWith(tempDir.resolve("unused.db")).initialize_loadSettings()

        // `D:` alone would be relative to the current folder of drive D
        assertEquals(if (Platform.isWindows) "D:/" else "/tmp/filemat", state.app.uploadFolderPath)
    }
}
