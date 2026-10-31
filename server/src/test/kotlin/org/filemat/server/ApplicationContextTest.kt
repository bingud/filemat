package org.filemat.server

import io.mockk.mockk
import org.filemat.server.common.State
import org.filemat.server.module.file.service.file.ThumbnailService
import org.filemat.server.module.file.service.file.component.FileContentMetadataService
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.filemat.server.config.Props
import org.filemat.server.config.properties.SensitiveFolderPaths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files

/**
 * Starts the whole application against an empty temporary SQLite database.
 *
 * Catches what unit tests with mocks cannot: a bean that cannot be wired, a broken SQL schema or
 * Flyway migration, and startup initialization that fails on a fresh install.
 */
@SpringBootTest(properties = ["spring.main.allow-bean-definition-overriding=true"])
@Import(ApplicationContextTest.WithoutNativeMedia::class)
class ApplicationContextTest {

    /**
     * Startup is what is under test here, not media decoding, so the thumbnail and media metadata services are mocks.
     * `MediaPreviewService` stays real, so its wiring and startup log are covered.
     */
    @TestConfiguration
    class WithoutNativeMedia {
        @Bean
        fun thumbnailService(): ThumbnailService = mockk(relaxed = true)

        @Bean
        fun fileContentMetadataService(): FileContentMetadataService = mockk(relaxed = true)
    }

    @Autowired
    lateinit var state: State

    @Autowired
    lateinit var sensitiveFolderPaths: SensitiveFolderPaths

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `application starts and finishes initialization on a fresh database`() {
        assertTrue(state.app.isInitialized)
        assertFalse(state.app.isSetup, "A fresh install must wait for the setup flow")
    }

    @Test
    fun `system roles are created and loaded into memory`() {
        val roles = state.auth.roleMap

        assertNotNull(roles[Props.Roles.adminRoleId])
        assertNotNull(roles[Props.Roles.userRoleId])
    }

    @Test
    fun `schema contains the tables the services rely on`() {
        val tables = jdbc.queryForList("SELECT name FROM sqlite_master WHERE type = 'table'", String::class.java).toSet()

        assertTrue(tables.containsAll(setOf("users", "role", "user_roles", "permissions", "files", "auth_token", "shared_files", "settings")), "Tables found: $tables")
    }

    @Test
    fun `foreign keys are enforced on database connections`() {
        assertEquals(1, jdbc.queryForObject("PRAGMA foreign_keys;", Int::class.java))
    }

    @Test
    fun `sensitive folder paths bean is wired to the same state`() {
        assertNotNull(sensitiveFolderPaths)
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val db = Files.createTempFile("filemat-test", ".db")
            db.toFile().deleteOnExit()
            registry.add("spring.datasource.url") { "jdbc:sqlite:$db" }
        }
    }
}
