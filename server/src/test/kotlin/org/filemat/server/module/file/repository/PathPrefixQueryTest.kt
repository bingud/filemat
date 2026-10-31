package org.filemat.server.module.file.repository

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.module.sharedFile.repository.SavedFileRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource

/**
 * Runs the path prefix SQL of the repositories on an in-memory SQLite database.
 * The SQL is read from the `@Query` annotations, so these tests check the real queries.
 */
class PathPrefixQueryTest {

    private val dataSource = SingleConnectionDataSource("jdbc:sqlite::memory:", true)
    private val jdbc = NamedParameterJdbcTemplate(dataSource)

    private val allPaths = listOf("/a_b", "/a_b/c", "/a_b/c/d", "/aXb", "/aXb/c", "/A_B/c", "/a_bc", "/a%b", "/a%b/x", "/a%bc")

    init {
        jdbc.jdbcTemplate.execute("CREATE TABLE files (entity_id TEXT, path TEXT, path_key TEXT)")
        jdbc.jdbcTemplate.execute("CREATE TABLE saved_files (user_id TEXT, path TEXT)")
    }

    @AfterEach
    fun close() = dataSource.destroy()

    private fun queryOf(repository: Class<*>, method: String, vararg parameterTypes: Class<*>): String =
        repository.getMethod(method, *parameterTypes).getAnnotation(Query::class.java).value

    private fun insertFiles(paths: List<String>) = paths.forEachIndexed { i, path ->
        jdbc.update("INSERT INTO files (entity_id, path, path_key) VALUES (:id, :path, :path)", mapOf("id" to "e$i", "path" to path))
    }

    private fun insertSavedFiles(userId: String, paths: List<String>) = paths.forEach { path ->
        jdbc.update("INSERT INTO saved_files (user_id, path) VALUES (:userId, :path)", mapOf("userId" to userId, "path" to path))
    }

    private fun savedPaths(): List<String> = jdbc.queryForList("SELECT path FROM saved_files", emptyMap<String, Any>(), String::class.java)

    private fun entitiesByPrefix(prefix: String): Set<String> {
        val sql = queryOf(EntityRepository::class.java, "getAllByPathPrefix", String::class.java)
        return jdbc.queryForList(sql, mapOf("prefixKey" to prefix)).map { it["path"] as String }.toSet()
    }

    @Test
    fun `entity prefix matches the path and its children only`() {
        insertFiles(allPaths)

        assertEquals(setOf("/a_b", "/a_b/c", "/a_b/c/d"), entitiesByPrefix("/a_b"))
    }

    @Test
    fun `entity prefix treats percent and underscore literally`() {
        insertFiles(allPaths)

        assertEquals(setOf("/a%b", "/a%b/x"), entitiesByPrefix("/a%b"))
        assertEquals(setOf("/aXb", "/aXb/c"), entitiesByPrefix("/aXb"))
    }

    @Test
    fun `entity prefix is case sensitive`() {
        insertFiles(allPaths)

        assertEquals(setOf("/A_B/c"), entitiesByPrefix("/A_B"))
    }

    @Test
    fun `entity prefix of a root matches everything below it`() {
        insertFiles(listOf("/", "/a", "/a/b", "C:/", "C:/a", "C:/a/b", "D:/a"))

        assertEquals(setOf("/", "/a", "/a/b"), entitiesByPrefix("/"))
        assertEquals(setOf("C:/", "C:/a", "C:/a/b"), entitiesByPrefix("C:/"))
    }

    @Test
    fun `saved file updatePath from a root keeps one separator`() {
        insertSavedFiles("u1", listOf("C:/a", "C:/a/b", "D:/a"))
        val sql = queryOf(SavedFileRepository::class.java, "updatePath", String::class.java, String::class.java)

        val updated = jdbc.update(sql, mapOf("path" to "C:/", "newPath" to "E:/moved"))

        assertEquals(2, updated)
        assertEquals(setOf("E:/moved/a", "E:/moved/a/b", "D:/a"), savedPaths().toSet())
    }

    @Test
    fun `saved file updatePath moves the path and its children only`() {
        insertSavedFiles("u1", allPaths)
        val sql = queryOf(SavedFileRepository::class.java, "updatePath", String::class.java, String::class.java)

        val updated = jdbc.update(sql, mapOf("path" to "/a_b", "newPath" to "/z"))

        assertEquals(3, updated)
        assertEquals(
            setOf("/z", "/z/c", "/z/c/d", "/aXb", "/aXb/c", "/A_B/c", "/a_bc", "/a%b", "/a%b/x", "/a%bc"),
            savedPaths().toSet(),
        )
    }

    @Test
    fun `saved file exists checks the user and the path`() {
        insertSavedFiles("u1", listOf("/p"))
        val sql = queryOf(SavedFileRepository::class.java, "exists", Ulid::class.java, String::class.java)

        fun exists(userId: String, path: String) = jdbc.queryForObject(sql, mapOf("userId" to userId, "path" to path), Boolean::class.java)

        assertEquals(true, exists("u1", "/p"))
        assertEquals(false, exists("u2", "/p"))
        assertEquals(false, exists("u1", "/q"))
    }
}
