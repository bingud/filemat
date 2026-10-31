package org.filemat.server.config.database

import org.filemat.server.config.platform.FileIdentity
import org.filemat.server.config.platform.PathPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.nio.file.Files
import java.nio.file.Path

class FileKeyBackfillTest {

    private val dataSource = SingleConnectionDataSource("jdbc:sqlite::memory:", true)
    private val jdbc = JdbcTemplate(dataSource)

    @TempDir
    lateinit var dir: Path

    init {
        jdbc.execute("CREATE TABLE files (entity_id TEXT PRIMARY KEY, path TEXT, is_filesystem_supported INTEGER, file_key TEXT)")
        jdbc.execute("CREATE UNIQUE INDEX idx_files_file_key ON files(file_key)")
    }

    @AfterEach
    fun close() = dataSource.destroy()

    private fun insert(id: String, path: String?, supported: Boolean = false, fileKey: String? = null) =
        jdbc.update("INSERT INTO files (entity_id, path, is_filesystem_supported, file_key) VALUES (?, ?, ?, ?)", id, path, supported, fileKey)

    private fun backfill() = dataSource.connection.let { FileKeyBackfill.run(it) }

    private fun row(id: String) = jdbc.queryForMap("SELECT is_filesystem_supported, file_key FROM files WHERE entity_id = ?", id)

    private fun file(name: String) = Files.writeString(dir.resolve(name), name)

    @Test
    fun `existing files get their key and are marked supported`() {
        val file = file("a.txt")
        insert("e1", PathPolicy.toApiString(file))

        assertEquals(1, backfill())
        assertEquals(FileIdentity.fileKey(file), row("e1")["file_key"])
        assertEquals(1, row("e1")["is_filesystem_supported"])
        assertEquals(0, backfill())
    }

    @Test
    fun `missing files and orphans are left alone`() {
        insert("e1", PathPolicy.toApiString(dir.resolve("missing.txt")), supported = true)
        insert("e2", null, supported = true)

        assertEquals(0, backfill())
        assertNull(row("e1")["file_key"])
        assertEquals(1, row("e1")["is_filesystem_supported"])
    }

    @Test
    fun `the older entity of a hard-linked file keeps the key`() {
        val file = file("a.txt")
        val link = Files.createLink(dir.resolve("link.txt"), file)
        insert("e1", PathPolicy.toApiString(file))
        insert("e2", PathPolicy.toApiString(link))

        assertEquals(2, backfill())
        assertEquals(FileIdentity.fileKey(file), row("e1")["file_key"])
        assertNull(row("e2")["file_key"])
        assertEquals(0, row("e2")["is_filesystem_supported"])
    }

    @Test
    fun `only supported entities are read when asked to`() {
        val file = file("a.txt")
        insert("e1", PathPolicy.toApiString(file), supported = false)

        assertEquals(0, dataSource.connection.let { FileKeyBackfill.run(it, onlySupported = true) })
        assertNull(row("e1")["file_key"])

        insert("e2", PathPolicy.toApiString(file), supported = true)
        assertEquals(1, dataSource.connection.let { FileKeyBackfill.run(it, onlySupported = true) })
        assertEquals(FileIdentity.fileKey(file), row("e2")["file_key"])
    }

    @Test
    fun `a key already stored on another entity is not taken`() {
        val file = file("a.txt")
        insert("e1", "/elsewhere", supported = true, fileKey = FileIdentity.fileKey(file))
        insert("e2", PathPolicy.toApiString(file))

        assertEquals(1, backfill())
        assertNull(row("e2")["file_key"])
        assertEquals(0, row("e2")["is_filesystem_supported"])
    }
}
