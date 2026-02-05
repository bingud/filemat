package db.migration

import org.filemat.server.common.util.parseUlidOrNull
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.permission.model.serialize
import org.filemat.server.module.role.model.Role
import org.filemat.server.module.role.model.RoleDto
import java.sql.Statement

class V5__remove_entity_follow_symlinks_column : Migration() {
    override fun execute() = "ALTER TABLE files DROP COLUMN follow_symlinks"
}