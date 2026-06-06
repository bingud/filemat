package db.migration

import java.sql.Statement

class V8__user_delete_fk_updates : Migration() {
    // Flyway wraps migrations in a transaction by default; SQLite cannot nest
    // transactions and PRAGMA foreign_keys cannot be toggled inside one.
    override fun canExecuteInTransaction(): Boolean = false

    override fun run(st: Statement) {
        st.execute("PRAGMA foreign_keys = OFF")

        try {
            st.execute("BEGIN IMMEDIATE")
            try {
                rebuildSavedFiles(st)
                rebuildFiles(st)
                st.execute("COMMIT")
            } catch (e: Exception) {
                st.execute("ROLLBACK")
                throw e
            }
        } finally {
            st.execute("PRAGMA foreign_keys = ON")
        }
    }

    private fun rebuildSavedFiles(st: Statement) {
        st.execute(
            """
            CREATE TABLE saved_files_new (
                user_id TEXT NOT NULL,
                path TEXT NOT NULL,
                created_date INTEGER NOT NULL,
                PRIMARY KEY (user_id, path),
                FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
            ) STRICT
            """.trimIndent()
        )

        st.execute(
            """
            INSERT INTO saved_files_new (user_id, path, created_date)
            SELECT sf.user_id, sf.path, sf.created_date
            FROM saved_files sf
            INNER JOIN users u ON sf.user_id = u.user_id
            """.trimIndent()
        )

        st.execute("DROP TABLE saved_files")
        st.execute("ALTER TABLE saved_files_new RENAME TO saved_files")
        st.execute("CREATE INDEX IF NOT EXISTS idx_saved_files_user_id ON saved_files(user_id)")
    }

    private fun rebuildFiles(st: Statement) {
        st.execute(
            """
            CREATE TABLE files_new (
                entity_id TEXT PRIMARY KEY,
                path TEXT,
                inode INTEGER,
                is_filesystem_supported INTEGER NOT NULL,
                owner_user_id TEXT,
                FOREIGN KEY (owner_user_id) REFERENCES users(user_id) ON DELETE SET NULL
            ) STRICT
            """.trimIndent()
        )

        st.execute(
            """
            INSERT INTO files_new (entity_id, path, inode, is_filesystem_supported, owner_user_id)
            SELECT entity_id, path, inode, is_filesystem_supported, owner_user_id
            FROM files
            """.trimIndent()
        )

        st.execute("DROP TABLE files")
        st.execute("ALTER TABLE files_new RENAME TO files")
        st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_files_path ON files(path)")
        st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_files_inode ON files(inode)")
    }
}
