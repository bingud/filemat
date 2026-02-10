package db.migration

import kotlinx.serialization.json.Json
import org.filemat.server.module.permission.model.SystemPermission
import java.sql.Statement

class V7__add_home_folder_permission_to_existing_roles : Migration() {
    override fun run(st: Statement) {
        val userRoleId = "008BG034N8XRRQ1MKHXNF9Y7RR"
        val adminRoleId = "005QMX54X0AMAS9Z66WXDGY8EX"

        val roles = mutableListOf<Pair<String, String>>()
        val roleResult = st.executeQuery("SELECT role_id, permissions FROM role WHERE role_id IN ('$userRoleId', '$adminRoleId')")

        while (roleResult.next()) {
            roles.add(roleResult.getString("role_id") to roleResult.getString("permissions"))
        }

        roles.forEach { (roleId, oldPermissionsString) ->
            val oldPermissions = SystemPermission.fromString(oldPermissionsString)
            val newPermissions = oldPermissions.toMutableSet()
            newPermissions.add(SystemPermission.CHANGE_OWN_HOME_FOLDER)

            val newPermissionsString = Json.encodeToString(newPermissions.map { it.index })

            // Use the statement now that the ResultSet is fully consumed and closed
            st.execute("UPDATE role SET permissions = '$newPermissionsString' WHERE role_id = '$roleId'")
        }
    }
}