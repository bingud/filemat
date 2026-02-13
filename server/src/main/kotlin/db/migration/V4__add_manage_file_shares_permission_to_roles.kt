package db.migration

import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.permission.model.serialize
import java.sql.Statement

class V4__add_manage_file_shares_permission_to_roles : Migration() {

    val systemPermissions = setOf(
        SystemPermission.ACCESS_ALL_FILES,
        SystemPermission.MANAGE_OWN_FILE_PERMISSIONS,
        SystemPermission.MANAGE_ALL_FILE_PERMISSIONS,
        SystemPermission.MANAGE_USERS,
        SystemPermission.MANAGE_SYSTEM,
        SystemPermission.EDIT_ROLES,
        SystemPermission.EXPOSE_FOLDERS,
        SystemPermission.SUPER_ADMIN,
    )

    val newPermission = SystemPermission.MANAGE_ALL_FILE_SHARES

    override fun run(st: Statement) {
        val roleResults = st.executeQuery("SELECT role_id, permissions FROM role")

        // UserID + PermissionList
        val updatedRows = mutableListOf<Pair<String, String>>()

        while (roleResults.next()) {
            val roleId = roleResults.getString("role_id")
            val permissionsStr = roleResults.getString("permissions")

            if (roleId != null && permissionsStr != null) {
                val permissions = SystemPermission.fromString(permissionsStr).toMutableSet()
                if (permissions.containsAll(systemPermissions)) {
                    permissions.add(newPermission)
                    val serialized = permissions.serialize()
                    updatedRows.add(roleId to serialized)
                }
            }
        }

        updatedRows.forEach { (roleId, newPermissions) ->
            try {
                st.execute("UPDATE role SET permissions = '$newPermissions' WHERE role_id = '$roleId'")
            } catch (e: Exception) {
                println("Migration V4 failed to update permissions of role with role ID '$roleId'")
                e.printStackTrace()
            }
        }
    }
}