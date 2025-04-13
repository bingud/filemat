package org.filemat.server.module.permission.model

import com.github.f4b6a3.ulid.Ulid


/**
 * Entity permission tree
 *
 * Stores file permissions based on paths, for users and roles
 */
class EntityPermissionTree {

    /**
     * File permission tree node
     */
    data class Node(
        // Path segment
        val segment: String,
        // Parent node
        val parent: Node?,
        // Node children
        val children: MutableMap<String, Node> = mutableMapOf(),
        // Permissions for users for this node
        val userPermissions: MutableMap<Ulid, EntityPermission> = mutableMapOf(),
        // Permissions for roles for this node
        val rolePermissions: MutableMap<Ulid, EntityPermission> = mutableMapOf(),
    )

    private val root = Node(segment = "", parent = null)

    /**
     * Add a permission for a path
     */
    fun addPermission(path: String, permission: EntityPermission) {
        val trim = path.trim('/')
        val segments = trim.split('/')
        var current = root

        if (trim.isNotBlank()) {
            // Descend or create nodes, attaching each child’s parent
            for (segment in segments) {
                current = current.children.getOrPut(segment) {
                    Node(segment = segment, parent = current)
                }
            }
        }

        // Store the permission based on its type
        when (permission.permissionType) {
            PermissionType.USER -> {
                check(permission.userId != null) { "User permission must have a non-null userId." }
                current.userPermissions[permission.userId] = permission
            }
            PermissionType.ROLE -> {
                check(permission.roleId != null) { "Role permission must have a non-null roleId." }
                current.rolePermissions[permission.roleId] = permission
            }
        }
    }

    /**
     * Gets the closest inherited permission for an input path, for user ID.
     *
     * Returns permission either for path or for closest parent.
     */
    fun getClosestPermissionForUser(path: String, userId: Ulid): EntityPermission? {
        val node = findNode(path, true) ?: return null
        var current: Node? = node
        while (current != null) {
            current.userPermissions[userId]?.let { return it }
            current = current.parent // move up
        }
        return null
    }

    /**
     * Gets the closest inherited permission for an input path, for role ID.
     *
     * Returns permission either for path or for closest parent.
     */
    fun getClosestPermissionForRole(path: String, roleId: Ulid): EntityPermission? {
        val node = findNode(path, true) ?: return null
        var current: Node? = node
        while (current != null) {
            current.rolePermissions[roleId]?.let { return it }
            current = current.parent
        }
        return null
    }


    /**
     * Gets the closest inherited permission for an input path, for list of role IDs.
     *
     * Returns permission either for path or for closest parent.
     */
    fun getClosestPermissionForAnyRole(path: String, roleIds: List<Ulid>): EntityPermission? {
        val node = findNode(path, true) ?: return null
        var current: Node? = node
        while (current != null) {
            for (roleId in roleIds) {
                current.rolePermissions[roleId]?.let { return it }
            }
            current = current.parent
        }
        return null
    }

    /**
     * Returns the node for the input path.
     */
    private fun findNode(path: String, onlyClosest: Boolean = false): Node? {
        val segments = path.trim('/').split('/')
        var current = root
        for (segment in segments) {
            val child = current.children[segment]
                ?: return if (onlyClosest) current else null
            current = child
        }

        return current
    }

    /**
     * Remove permission with a specific entity ID from a specific path
     */
    fun removePermissionByEntityId(path: String, entityId: Ulid, permissionType: PermissionType?) {
        val node = findNode(path) ?: return

        if (permissionType == PermissionType.USER || permissionType == null) {
            node.userPermissions.remove(entityId)
        }
        if (permissionType == PermissionType.ROLE || permissionType == null) {
            node.rolePermissions.remove(entityId)
        }

    }

    /**
     * Update permission for a specific entity ID on a specific path
     */
    fun updatePermissionPath(oldPath: String, newPath: String?, entityId: Ulid, permissionType: PermissionType?) {
        // Move user permission
        if (permissionType == PermissionType.USER || permissionType == null) {
            val oldPermission = getClosestPermissionForUser(oldPath, entityId)
            removePermissionByEntityId(oldPath, entityId, PermissionType.USER)
            if (!newPath.isNullOrBlank() && oldPermission != null) {
                addPermission(newPath, oldPermission)
            }
        }

        if (permissionType == PermissionType.ROLE || permissionType == null) {
            val oldPermission = getClosestPermissionForRole(oldPath, entityId)
            removePermissionByEntityId(oldPath, entityId, PermissionType.ROLE)
            if (!newPath.isNullOrBlank() && oldPermission != null) {
                addPermission(newPath, oldPermission)
            }
        }
    }

    fun getAllPermissionsForPath(path: String): List<EntityPermission> {
        val node = findNode(path) ?: return emptyList()
        return collectAllPermissions(node)
    }

    private fun collectAllPermissions(node: Node): List<EntityPermission> {
        val results = mutableListOf<EntityPermission>()
        results.addAll(node.userPermissions.values)
        results.addAll(node.rolePermissions.values)

        return results
    }

    fun getDirectPermissionForUser(path: String, userId: Ulid): EntityPermission? {
        val node = findNode(path, onlyClosest = false) ?: return null
        println(node.segment)
        println(node.userPermissions[userId])
        return node.userPermissions[userId]
    }
    fun getDirectPermissionForRole(path: String, roleId: Ulid): EntityPermission? {
        val node = findNode(path, onlyClosest = false) ?: return null
        return node.rolePermissions[roleId]
    }

    fun getPermissionById(permissionId: Ulid): EntityPermission? {
        return findPermissionById(root, permissionId)
    }

    private fun findPermissionById(node: Node, permissionId: Ulid): EntityPermission? {
        // Check userPermissions
        node.userPermissions.values.firstOrNull { it.permissionId == permissionId }?.let { return it }
        // Check rolePermissions
        node.rolePermissions.values.firstOrNull { it.permissionId == permissionId }?.let { return it }

        // Recurse into children
        for (child in node.children.values) {
            findPermissionById(child, permissionId)?.let { return it }
        }

        return null
    }

}
