package db.migration

import org.filemat.server.common.util.parseUlidOrNull
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.permission.model.serialize
import org.filemat.server.module.role.model.Role
import org.filemat.server.module.role.model.RoleDto
import java.sql.Statement

class V6__add_home_folder_path_column : Migration() {
    override fun execute() = "ALTER TABLE users ADD COLUMN home_folder_path TEXT DEFAULT NULL"
}