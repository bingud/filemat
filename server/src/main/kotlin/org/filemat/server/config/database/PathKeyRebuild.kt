package org.filemat.server.config.database

import org.filemat.server.config.platform.PathPolicy
import java.nio.file.Files
import java.sql.Connection

/**
 * Recomputes `files.path_key` from `files.path` (§2.3).
 *
 * On Windows a key depends on the case-sensitivity flags of folders, which can change while the server is stopped.
 * When two entities end up with the same key, the older one keeps it and the newer one loses its path,
 * like an entity whose file was deleted.
 */
object PathKeyRebuild {
    private data class Row(val entityId: String, val path: String, val pathKey: String?)

    /**
     * Returns the number of changed entities. Runs in its own transaction unless the connection is already in one.
     */
    fun run(connection: Connection): Int = inOwnTransaction(connection) { rebuild(connection) }

    private fun rebuild(connection: Connection): Int {
        val rows = mutableListOf<Row>()
        connection.createStatement().use { st ->
            // ULIDs sort by creation time, so the older entity comes first
            st.executeQuery("SELECT entity_id, path, path_key FROM files WHERE path IS NOT NULL ORDER BY entity_id").use { rs ->
                while (rs.next()) rows += Row(rs.getString("entity_id"), rs.getString("path"), rs.getString("path_key"))
            }
        }

        val seenKeys = HashSet<String>()
        val changes = rows.mapNotNull { row ->
            // A missing folder reads as case-insensitive. Its stored key stays, or an unplugged drive would fold the keys of its
            // case-sensitive folders, and `a` and `A` there would collide.
            val key = row.pathKey?.takeIf { isParentFolderMissing(row.path) } ?: PathPolicy.pathKey(row.path)
            when {
                !seenKeys.add(key) -> row to null
                key != row.pathKey -> row to key
                else -> null
            }
        }
        if (changes.isEmpty()) return 0

        // Clear the keys first, so the unique index never sees two rows with the same key
        connection.prepareStatement("UPDATE files SET path_key = NULL WHERE entity_id = ?").use { st ->
            changes.forEach { (row, _) ->
                st.setString(1, row.entityId)
                st.executeUpdate()
            }
        }
        connection.prepareStatement("UPDATE files SET path = ?, path_key = ? WHERE entity_id = ?").use { st ->
            changes.forEach { (row, key) ->
                st.setString(1, if (key == null) null else row.path)
                st.setString(2, key)
                st.setString(3, row.entityId)
                st.executeUpdate()
            }
        }
        return changes.size
    }

    private fun isParentFolderMissing(path: String): Boolean {
        val parent = runCatching { PathPolicy.parse(path).parent }.getOrNull() ?: return false
        return !Files.isDirectory(parent)
    }
}
