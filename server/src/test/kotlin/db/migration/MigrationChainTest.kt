package db.migration

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.nio.file.Path

class MigrationChainTest {

    @TempDir
    lateinit var dir: Path

    private fun columns(jdbc: JdbcTemplate, table: String) =
        jdbc.queryForList("SELECT name FROM pragma_table_info('$table')", String::class.java)

    private fun indexes(jdbc: JdbcTemplate, table: String) =
        jdbc.queryForList("SELECT name FROM pragma_index_list('$table')", String::class.java)

    @Test
    fun `the schema plus every migration ends without the inode column`() {
        val url = "jdbc:sqlite:${dir.resolve("test.db")}"
        val dataSource = SingleConnectionDataSource(url, true)
        try {
            val jdbc = JdbcTemplate(dataSource)
            // Same as DatabaseSetup.initialize_setUpSchema, which runs on every start
            fun runSchema() = ClassPathResource("sqlite-schema.sql").inputStream.bufferedReader().use { it.readText() }
                .split(";").map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { jdbc.execute(it) }
            runSchema()
            jdbc.update("INSERT INTO files (entity_id, path, inode, is_filesystem_supported) VALUES ('e1', '/missing/a.txt', 42, 1)")

            Flyway.configure().dataSource(url, null, null).baselineOnMigrate(true).load().migrate()

            val files = columns(jdbc, "files")
            assertFalse("inode" in files, files.toString())
            assertTrue("file_key" in files && "path_key" in files, files.toString())
            assertFalse("idx_files_inode" in indexes(jdbc, "files"))
            assertEquals("/missing/a.txt", jdbc.queryForObject("SELECT path FROM files WHERE entity_id = 'e1'", String::class.java))

            // The next start runs the schema again on the migrated database
            runSchema()
            assertFalse("inode" in columns(jdbc, "files"))
        } finally {
            dataSource.destroy()
        }
    }
}
