package org.filemat.server.config.database

import org.filemat.server.config.platform.FileIdentity
import org.filemat.server.config.platform.PathPolicy
import java.sql.Connection

/**
 * Fills `files.file_key` for entities whose file exists (§3.5), and stores again whether their filesystem has stable keys.
 *
 * Entities whose file is missing keep a null key and get one when the file is found again.
 * Hard links share a key, which goes to the older entity. The newer one follows its path only.
 */
object FileKeyBackfill {
    private data class Row(val entityId: String, val path: String)
    private data class Change(val entityId: String, val fileKey: String?, val isSupported: Boolean)

    private const val progressStep = 1_000

    /**
     * Returns the number of changed entities. Runs in its own transaction unless the connection is already in one.
     * With [onlySupported], entities marked as on an unsupported filesystem are left out: they follow their path and have no key to repair.
     */
    fun run(connection: Connection, onlySupported: Boolean = false): Int =
        inOwnTransaction(connection) { backfill(connection, onlySupported) }

    private fun backfill(connection: Connection, onlySupported: Boolean): Int {
        val rows = mutableListOf<Row>()
        val takenKeys = HashSet<String>()
        val supportedOnly = if (onlySupported) " AND is_filesystem_supported = 1" else ""
        connection.createStatement().use { st ->
            // ULIDs sort by creation time, so the older entity comes first
            st.executeQuery("SELECT entity_id, path FROM files WHERE path IS NOT NULL AND file_key IS NULL$supportedOnly ORDER BY entity_id").use { rs ->
                while (rs.next()) rows += Row(rs.getString("entity_id"), rs.getString("path"))
            }
            st.executeQuery("SELECT file_key FROM files WHERE file_key IS NOT NULL").use { rs ->
                while (rs.next()) takenKeys += rs.getString("file_key")
            }
        }

        val changes = mutableListOf<Change>()
        rows.forEachIndexed { index, row ->
            if (index > 0 && index % progressStep == 0) println("Reading file keys: $index of ${rows.size}")

            val path = runCatching { PathPolicy.parse(row.path) }.getOrNull() ?: return@forEachIndexed
            val isSupported = FileIdentity.isSupported(path) ?: return@forEachIndexed
            if (!isSupported) {
                changes += Change(row.entityId, null, false)
                return@forEachIndexed
            }

            val fileKey = FileIdentity.fileKey(path) ?: return@forEachIndexed
            changes += if (takenKeys.add(fileKey)) Change(row.entityId, fileKey, true) else Change(row.entityId, null, false)
        }
        if (changes.isEmpty()) return 0

        connection.prepareStatement("UPDATE files SET file_key = ?, is_filesystem_supported = ? WHERE entity_id = ?").use { st ->
            changes.forEach { change ->
                st.setString(1, change.fileKey)
                st.setBoolean(2, change.isSupported)
                st.setString(3, change.entityId)
                st.executeUpdate()
            }
        }
        return changes.size
    }
}
