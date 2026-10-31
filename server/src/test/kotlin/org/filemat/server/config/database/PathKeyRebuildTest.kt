package org.filemat.server.config.database

import org.filemat.server.config.platform.PathPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource

class PathKeyRebuildTest {

    private val dataSource = SingleConnectionDataSource("jdbc:sqlite::memory:", true)
    private val jdbc = JdbcTemplate(dataSource)

    init {
        jdbc.execute("CREATE TABLE files (entity_id TEXT PRIMARY KEY, path TEXT, path_key TEXT)")
        jdbc.execute("CREATE UNIQUE INDEX idx_files_path_key ON files(path_key)")
    }

    @AfterEach
    fun close() = dataSource.destroy()

    private fun insert(id: String, path: String?, pathKey: String?) =
        jdbc.update("INSERT INTO files (entity_id, path, path_key) VALUES (?, ?, ?)", id, path, pathKey)

    private fun rebuild() = dataSource.connection.let { PathKeyRebuild.run(it) }

    private fun row(id: String) = jdbc.queryForMap("SELECT path, path_key FROM files WHERE entity_id = ?", id)

    @Test
    fun `fills missing keys and leaves correct keys alone`() {
        insert("e1", "/a", null)
        insert("e2", "/b", PathPolicy.pathKey("/b"))
        insert("e3", null, null)

        assertEquals(1, rebuild())
        assertEquals(PathPolicy.pathKey("/a"), row("e1")["path_key"])
        assertNull(row("e3")["path_key"])
        assertEquals(0, rebuild())
    }

    @Test
    fun `keys can trade places without breaking the unique index`() {
        insert("e1", "/b", PathPolicy.pathKey("/a"))
        insert("e2", "/a", PathPolicy.pathKey("/b"))

        assertEquals(2, rebuild())
        assertEquals(PathPolicy.pathKey("/b"), row("e1")["path_key"])
        assertEquals(PathPolicy.pathKey("/a"), row("e2")["path_key"])
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `the older entity keeps a key that two paths share`() {
        insert("e1", "C:/fm-missing/a.txt", null)
        insert("e2", "C:/fm-missing/A.txt", null)

        assertEquals(2, rebuild())
        assertEquals("C:/FM-MISSING/A.TXT", row("e1")["path_key"])
        assertNull(row("e2")["path"])
        assertNull(row("e2")["path_key"])
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `keys in a folder that is missing stay, so an unplugged case-sensitive folder keeps a and A apart`() {
        // As stored while the folder was there and case-sensitive
        insert("e1", "C:/fm-missing/cs/a.txt", "C:/FM-MISSING/CS/a.txt")
        insert("e2", "C:/fm-missing/cs/A.txt", "C:/FM-MISSING/CS/A.txt")

        assertEquals(0, rebuild())
        assertEquals("C:/fm-missing/cs/a.txt", row("e1")["path"])
        assertEquals("C:/fm-missing/cs/A.txt", row("e2")["path"])
        assertEquals("C:/FM-MISSING/CS/a.txt", row("e1")["path_key"])
    }
}
