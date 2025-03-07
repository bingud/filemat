package org.filemat.server.module.permission.model

import com.github.f4b6a3.ulid.Ulid


/**
 * Represents a node in the file path tree.
 * Each node holds:
 *  - children: mapping from path segment to child node
 *  - userPermissions: mapping from userId -> EntityPermission
 *  - rolePermissions: mapping from roleId -> EntityPermission
 */
class EntityPermissionTree {

    private val root = Node("")

    data class Node(
        val segment: String,
        val children: MutableMap<String, Node> = mutableMapOf(),
        val userPermissions: MutableMap<Ulid, EntityPermission> = mutableMapOf(),
        val rolePermissions: MutableMap<Ulid, EntityPermission> = mutableMapOf()
    )

    /**
     * Inserts or updates a permission into the tree at the specified path.
     * A path is split by '/' into segments. The corresponding node is created if missing.
     */
    fun addPermission(path: String, permission: EntityPermission) {
        val segments = path.trim('/').split('/')
        var current = root

        // Descend or create nodes
        for (segment in segments) {
            current = current.children.getOrPut(segment) { Node(segment) }
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
     * Returns the closest permission for the specified userId, or null if not found
     * at this path or any of its parents.
     */
    fun getClosestPermissionForUser(path: String, userId: Ulid): EntityPermission? {
        val node = findNode(path) ?: return null
        // Climb up until we find a node with a matching user permission or reach the root
        var current: Node? = node
        while (current != null) {
            current.userPermissions[userId]?.let { return it }
            current = findParentNode(path, current)
        }
        return null
    }

    /**
     * Returns the closest permission for the specified roleId, or null if not found
     * at this path or any of its parents.
     */
    fun getClosestPermissionForRole(path: String, roleId: Ulid): EntityPermission? {
        val node = findNode(path) ?: return null
        var current: Node? = node
        while (current != null) {
            current.rolePermissions[roleId]?.let { return it }
            current = findParentNode(path, current)
        }
        return null
    }

    /**
     * Returns the closest permission for any of the specified roleIds. You can adapt
     * how you combine or prioritize multiple role matches. Here we simply return the
     * first found, but you could merge permissions if needed.
     */
    fun getClosestPermissionForAnyRole(path: String, roleIds: List<Ulid>): EntityPermission? {
        val node = findNode(path) ?: return null
        var current: Node? = node
        while (current != null) {
            // Check if current node has a permission for any of the given roles
            for (roleId in roleIds) {
                current.rolePermissions[roleId]?.let { return it }
            }
            current = findParentNode(path, current)
        }
        return null
    }

    /**
     * Locates the node that corresponds exactly to the given path, or null if it doesn't exist.
     */
    private fun findNode(path: String): Node? {
        val segments = path.trim('/').split('/')
        var current = root
        for (segment in segments) {
            val next = current.children[segment] ?: return null
            current = next
        }
        return current
    }

    /**
     * Utility to move "one level up" from the given node. We do this by reconstructing
     * the path from the root, then dropping the last segment to find the parent node.
     *
     * If the current node is the root, or we can't find a parent, we return null.
     */
    private fun findParentNode(fullPath: String, currentNode: Node): Node? {
        if (currentNode == root) return null

        // Re-split and remove last segment
        val segments = fullPath.trim('/').split('/')
        if (segments.size <= 1) return root

        // Construct the parent path
        val parentSegments = segments.dropLast(1)
        val parentPath = parentSegments.joinToString("/")
        return findNode(parentPath)
    }

    /**
     * Removes a permission entry from the specified path by entity ID.
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
     * Updates the path for a specific entity's permission by removing it from the old path
     * and inserting it under the new path (if the new path is not null/blank).
     */
    fun updatePermissionPath(oldPath: String, newPath: String?, entityId: Ulid, permissionType: PermissionType) {
        // Fetch current permission at the old path
        val oldPermission = when (permissionType) {
            PermissionType.USER -> getClosestPermissionForUser(oldPath, entityId)
            PermissionType.ROLE -> getClosestPermissionForRole(oldPath, entityId)
        } ?: return

        // Remove it from the old path
        removePermissionByEntityId(oldPath, entityId, permissionType)

        // Re-insert under the new path if provided
        if (!newPath.isNullOrBlank()) {
            addPermission(newPath, oldPermission)
        }
    }
}