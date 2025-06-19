package org.filemat.server.module.permission.model

import com.github.f4b6a3.ulid.Ulid
import java.util.concurrent.ConcurrentHashMap


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
        val children: ConcurrentHashMap<String, Node> = ConcurrentHashMap(),
        // Permissions for users for this node
        val userPermissions: ConcurrentHashMap<Ulid, EntityPermission> = ConcurrentHashMap(),
        // Permissions for roles for this node
        val rolePermissions: ConcurrentHashMap<Ulid, EntityPermission> = ConcurrentHashMap(),
    )

    private val root = Node(segment = "", parent = null)

    private fun getOrCreateTreePath(path: String): Node {
        val trim = path.trim('/')
        val segments = trim.split('/')
        var current = root

        if (trim.isNotBlank()) {
            // Descend or create nodes, attaching each child’s parent
            for (segment in segments) {
                current = current.children.getOrPut(segment) {
                    return@getOrPut Node(segment = segment, parent = current)
                }
            }
        }

        return current
    }

    /**
     * Add a permission for a path
     */
    fun addPermission(path: String, permission: EntityPermission) {
        val node = getOrCreateTreePath(path)

        // Store the permission based on its type
        when (permission.permissionType) {
            PermissionType.USER -> {
                check(permission.userId != null) { "User permission must have a non-null userId." }
                node.userPermissions[permission.userId] = permission
            }
            PermissionType.ROLE -> {
                check(permission.roleId != null) { "Role permission must have a non-null roleId." }
                node.rolePermissions[permission.roleId] = permission
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
    private fun findNode(path: String, getClosestNode: Boolean = false): Node? {
        if (path == "/") return root
        val segments = path.trim('/').split('/')
        var current = root

        for (segment in segments) {
            val child = current.children[segment]
                ?: return if (getClosestNode) current else null
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
    fun updateEntityPath(oldPath: String, newPath: String, entityId: Ulid): Boolean {
        val oldNode = findNode(oldPath, getClosestNode = false) ?: return false
        val userPermissions = oldNode.userPermissions.filter { it.value.entityId == entityId }
        val rolePermissions = oldNode.rolePermissions.filter { it.value.entityId == entityId }

        val existingNewNode = findNode(newPath, getClosestNode = false)
        if (existingNewNode != null) return false

        val newNode = getOrCreateTreePath(newPath)
        newNode.userPermissions.putAll(userPermissions)
        newNode.rolePermissions.putAll(rolePermissions)

        return true
    }

    fun getAllPermissionsForPath(path: String): List<EntityPermission> {
        val node = findNode(path, getClosestNode = false) ?: return emptyList()
        return collectAllPermissions(node)
    }

    private fun collectAllPermissions(node: Node): List<EntityPermission> {
        val results = mutableListOf<EntityPermission>()
        results.addAll(node.userPermissions.values)
        results.addAll(node.rolePermissions.values)

        return results
    }

    fun getDirectPermissionForUser(path: String, userId: Ulid): EntityPermission? {
        val node = findNode(path, getClosestNode = false) ?: return null
        return node.userPermissions[userId]
    }
    fun getDirectPermissionForRole(path: String, roleId: Ulid): EntityPermission? {
        val node = findNode(path, getClosestNode = false) ?: return null
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

    /**
     * Removes a permission by permissionId from the entire tree.
     * Returns true if a matching permission was found and removed; false if not.
     */
    fun removePermissionByPermissionId(permissionId: Ulid): Boolean {
        return removePermissionByPermissionIdRecursive(root, permissionId)
    }

    private fun removePermissionByPermissionIdRecursive(node: Node, permissionId: Ulid): Boolean {
        // Try removing in userPermissions
        val userKey = node.userPermissions.entries.find { it.value.permissionId == permissionId }?.key
        if (userKey != null) {
            node.userPermissions.remove(userKey)
            return true
        }

        // Try removing in rolePermissions
        val roleKey = node.rolePermissions.entries.find { it.value.permissionId == permissionId }?.key
        if (roleKey != null) {
            node.rolePermissions.remove(roleKey)
            return true
        }

        // Recurse into children
        for (child in node.children.values) {
            if (removePermissionByPermissionIdRecursive(child, permissionId)) {
                return true
            }
        }
        return false
    }
}
