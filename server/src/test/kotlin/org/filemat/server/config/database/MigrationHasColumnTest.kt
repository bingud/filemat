package org.filemat.server.config.database

import db.migration.Migration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.Statement

/**
 * `Migration.hasColumn` guards V9 against a column that is already there. Not in `db.migration`, so Flyway doesn't find the probe.
 */
class MigrationHasColumnTest {

    @Suppress("ClassName")
    private class V1__HasColumnProbe : Migration() {
        fun has(st: Statement, table: String, column: String) = hasColumn(st, table, column)
    }

    @Test
    fun `hasColumn finds only the columns of the table`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { st ->
                st.execute("CREATE TABLE files (path TEXT, path_key TEXT)")
                val probe = V1__HasColumnProbe()

                assertTrue(probe.has(st, "files", "path_key"))
                assertFalse(probe.has(st, "files", "inode"))
                assertFalse(probe.has(st, "missing", "path"))
            }
        }
    }
}
