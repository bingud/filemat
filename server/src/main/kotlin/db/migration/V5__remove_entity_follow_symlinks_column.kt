package db.migration

import org.filemat.server.common.util.parseUlidOrNull
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.permission.model.serialize
import org.filemat.server.module.role.model.Role
import org.filemat.server.module.role.model.RoleDto
import java.sql.ResultSet
import java.sql.Statement

class V5__remove_entity_follow_symlinks_column : Migration() {
    override fun run(st: Statement) {
        val rs: ResultSet = st.executeQuery("PRAGMA table_info(files)")
        var hasCol = false
        while (rs.next()) {
            if (rs.getString("name") == "follow_symlinks") {
                hasCol = true
                break
            }
        }
        if (hasCol) {
            st.execute(
                "ALTER TABLE files DROP COLUMN follow_symlinks"
            )
        }
    }


//    override fun execute() = "ALTER TABLE files DROP COLUMN IF EXISTS follow_symlinks;"
}