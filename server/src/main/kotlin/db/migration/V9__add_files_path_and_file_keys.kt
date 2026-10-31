package db.migration

import org.filemat.server.config.database.FileKeyBackfill
import org.filemat.server.config.database.PathKeyRebuild
import org.filemat.server.config.platform.Platform
import java.sql.Statement

/**
 * Adds `files.path_key`, the lookup key of an entity path (§2.3). On Linux the key is the path.
 *
 * Adds `files.file_key`, the identity of an entity's file (§3), and drops `inode`, which it replaces.
 * `sqlite-schema.sql` still creates `inode`, because it is the baseline that V8 copies the column from.
 */
class V9__add_files_path_and_file_keys : Migration() {
    // The `hasColumn` guards make V9 safe to run on a database that already has part of it (docs/database.md asks this of every migration).
    override fun run(st: Statement) {
        if (!hasColumn(st, "files", "path_key")) {
            st.execute("ALTER TABLE files ADD COLUMN path_key TEXT")
        }

        // Windows rebuilds the keys again on every start (DatabaseSetup.initialize_rebuildPathKeys), because folder flags can change while stopped
        if (Platform.isWindows) {
            PathKeyRebuild.run(st.connection)
        } else {
            st.execute("UPDATE files SET path_key = path")
        }

        st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_files_path_key ON files(path_key)")

        if (!hasColumn(st, "files", "file_key")) {
            st.execute("ALTER TABLE files ADD COLUMN file_key TEXT")
        }

        val changed = FileKeyBackfill.run(st.connection)
        if (changed > 0) println("Added file keys to $changed indexed files.")

        st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_files_file_key ON files(file_key)")

        // SQLite can't drop an indexed column
        st.execute("DROP INDEX IF EXISTS idx_files_inode")
        if (hasColumn(st, "files", "inode")) {
            st.execute("ALTER TABLE files DROP COLUMN inode")
        }
    }
}
