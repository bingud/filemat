package db.migration

import org.filemat.server.common.util.parseUlidOrNull
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.permission.model.serialize
import org.filemat.server.module.role.model.Role
import org.filemat.server.module.role.model.RoleDto
import java.sql.Statement

class V4__add_manage_file_shares_permission_to_roles : Migration() {
    override fun run(st: Statement) {
        val count = SystemPermission.entries.size
        val previousFullCount = count - 1

        val roleResults = st.executeQuery("SELECT * FROM role")
        val roles = mutableListOf<Role>()
        while (roleResults.next()) {
            roles.add(RoleDto(
                roleId = parseUlidOrNull(roleResults.getString("role_id")) ?: throw IllegalStateException("While executing database migration, role ID was invalid ID."),
                name = roleResults.getString("name"),
                createdDate = roleResults.getLong("created_date"),
                permissions = roleResults.getString("permissions")
            ).toRole())
        }

        val newPermissions = SystemPermission.entries
        val serializedPermissions = newPermissions.serialize()
        roles.forEach { role ->
            if (role.permissions.size == previousFullCount) {
                st.execute("UPDATE role SET permissions = '$serializedPermissions' WHERE role_id = '${role.roleId}'")
            }
        }
    }
}