package db.migration

import java.sql.Statement

class V9__add_file_path_and_identity_keys : Migration() {
    override fun canExecuteInTransaction(): Boolean = false

    override fun run(st: Statement) {
        st.execute("PRAGMA foreign_keys = OFF")

        try {
            st.execute("BEGIN IMMEDIATE")
            try {
                rebuildSavedFiles(st)
                rebuildFolderVisibility(st)
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

    private fun rebuildFiles(st: Statement) {
        st.execute(
            """
            CREATE TABLE files_new (
                entity_id TEXT PRIMARY KEY,
                path TEXT,
                path_key TEXT,
                inode INTEGER,
                file_key TEXT,
                is_filesystem_supported INTEGER NOT NULL,
                owner_user_id TEXT,
                FOREIGN KEY (owner_user_id) REFERENCES users(user_id) ON DELETE SET NULL
            ) STRICT
            """.trimIndent()
        )

        st.execute(
            """
            INSERT INTO files_new (entity_id, path, path_key, inode, file_key, is_filesystem_supported, owner_user_id)
            SELECT entity_id, path, path, inode,
                   CASE WHEN inode IS NOT NULL THEN 'legacy-inode:' || inode ELSE NULL END,
                   is_filesystem_supported, owner_user_id
            FROM files
            """.trimIndent()
        )

        st.execute("DROP TABLE files")
        st.execute("ALTER TABLE files_new RENAME TO files")
        st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_files_path ON files(path)")
        st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_files_path_key ON files(path_key)")
        st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_files_inode ON files(inode)")
        st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_files_file_key ON files(file_key)")
    }

    private fun rebuildSavedFiles(st: Statement) {
        st.execute(
            """
            CREATE TABLE saved_files_new (
                user_id TEXT NOT NULL,
                path TEXT NOT NULL,
                path_key TEXT NOT NULL,
                created_date INTEGER NOT NULL,
                PRIMARY KEY (user_id, path_key),
                FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
            ) STRICT
            """.trimIndent()
        )

        st.execute(
            """
            INSERT OR IGNORE INTO saved_files_new (user_id, path, path_key, created_date)
            SELECT user_id, path, path, created_date
            FROM saved_files
            """.trimIndent()
        )

        st.execute("DROP TABLE saved_files")
        st.execute("ALTER TABLE saved_files_new RENAME TO saved_files")
        st.execute("CREATE INDEX IF NOT EXISTS idx_saved_files_user_id ON saved_files(user_id)")
    }

    private fun rebuildFolderVisibility(st: Statement) {
        st.execute(
            """
            CREATE TABLE folder_visibility_new (
                path TEXT PRIMARY KEY,
                path_key TEXT,
                is_exposed INTEGER NOT NULL,
                created_date INTEGER NOT NULL
            ) STRICT
            """.trimIndent()
        )

        st.execute(
            """
            INSERT OR IGNORE INTO folder_visibility_new (path, path_key, is_exposed, created_date)
            SELECT path, path, is_exposed, created_date
            FROM folder_visibility
            """.trimIndent()
        )

        st.execute("DROP TABLE folder_visibility")
        st.execute("ALTER TABLE folder_visibility_new RENAME TO folder_visibility")
        st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_folder_visibility_path_key ON folder_visibility(path_key)")
    }
}
