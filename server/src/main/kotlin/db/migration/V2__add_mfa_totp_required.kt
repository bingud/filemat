package db.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import java.sql.ResultSet
import java.sql.Statement

class V2__add_mfa_totp_required : Migration() {
    override fun run(st: Statement) {
        val rs: ResultSet = st.executeQuery("PRAGMA table_info(users)")
        var hasCol = false
        while (rs.next()) {
            if (rs.getString("name") == "mfa_totp_required") {
                hasCol = true
                break
            }
        }
        if (!hasCol) {
            st.execute(
                "ALTER TABLE users ADD COLUMN mfa_totp_required INTEGER NOT NULL DEFAULT 0"
            )
        }
    }
}