package org.filemat.server.module.permission.model

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.common.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write


/**
* A concurrent prefix tree (Trie) for efficient file permission storage and retrieval.
*
* Structure:
* - Each node represents a path segment.
* - Nodes store direct User and Role permissions.
*
* Key Operations:
* - resolveEffectivePermissions: Traverses leaf-to-root to find the most specific permission.
* - getAllAccessibleEntitiesForUser: Traverses root-to-leaf to find accessible entry points.
*
* Thread Safety:
* - Uses a ReentrantReadWriteLock (`treeLock`) for all tree modifications.
* - Uses `permissionsIndexLock` for maintaining inverted indexes.
*/
class EntityPermissionTree() {

    /**
     * File permission tree node
     */
    data class Node(
        // Path segment
        var segment: String,
        // Parent node
        var parent: Node?,
        // Node children
        val children: ConcurrentHashMap<String, Node> = ConcurrentHashMap(),
        // Permissions for users for this node
        val userPermissions: ConcurrentHashMap<Ulid, EntityPermission> = ConcurrentHashMap(),
        // Permissions for roles for this node
        val rolePermissions: ConcurrentHashMap<Ulid, EntityPermission> = ConcurrentHashMap(),
    )

    private val root = Node(segment = "", parent = null)

    // inverted indexes
    // EntityPermission `equals` and `hashCode` is based on `permissionId`
    private val userPermissionsIndex = ConcurrentHashMap<Ulid, ConcurrentHashMap.KeySetView<EntityPermission, Boolean>>() // UserID -> Permissions
    private val rolePermissionsIndex = ConcurrentHashMap<Ulid, ConcurrentHashMap.KeySetView<EntityPermission, Boolean>>() // RoleID -> Permissions
    private val permissionsIndexLock = ReentrantReadWriteLock()

    private val treeLock = ReentrantReadWriteLock()

    /**
     * Removes a permission from the inverted index.
     */
    private fun permissionIndex_remove(permission: EntityPermission) {
        if (permission.permissionType == PermissionType.USER) {
            val set = userPermissionsIndex[permission.userId]
            if (set != null) {
                set.remove(permission)
                if (set.isEmpty()) {
                    userPermissionsIndex.remove(permission.userId)
                }
            }
        } else {
            val set = rolePermissionsIndex[permission.roleId]
            if (set != null) {
                set.remove(permission)
                if (set.isEmpty()) {
                    rolePermissionsIndex.remove(permission.roleId)
                }
            }
        }
    }

    /**
     * Adds a permission to the inverted index.
     */
    private fun permissionIndex_put(permission: EntityPermission) {
        if (permission.permissionType == PermissionType.USER) {
            permission.userId ?: throw NullPointerException("Cannot insert role permission into user permission index (userId is null)")
            val set = userPermissionsIndex.computeIfAbsent(permission.userId) { concurrentMutableSetOf() }
            set.replace(permission)
        } else {
            permission.roleId ?: throw NullPointerException("Cannot insert user permission into role permission index (roleId is null)")
            val set = rolePermissionsIndex.computeIfAbsent(permission.roleId) { concurrentMutableSetOf() }
            set.replace(permission)
        }
    }


    /**
     * Gets or creates all nodes along the path, returning the leaf node.
     */
    private fun getOrCreateTreePath(path: String): Node {
        treeLock.write {
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
    }

    /**
     * Add a permission for a path
     */
    fun addPermission(path: String, permission: EntityPermission) {
        val node = getOrCreateTreePath(path)

        // Store the permission based on its type
        treeLock.write {
            permissionsIndexLock.write {
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

                permissionIndex_put(permission)
            }
        }
    }

    /**
     * Returns the node for the input path.
     */
    private fun findNode(path: String, getClosestNode: Boolean = false): Node? {
        if (path == "/") return root
        val segments = path.trim('/').split('/')
        var current = root


        treeLock.read {
            for (segment in segments) {
                val child = current.children[segment]
                    ?: return if (getClosestNode) current else null
                current = child
            }
        }

        return current
    }

    /**
     * Remove permission with a specific entity ID from a specific path
     */
    fun removePermissionByEntityId(path: String, entityId: Ulid, permissionType: PermissionType?) {
        val node = findNode(path) ?: return

        treeLock.write {
            permissionsIndexLock.write {
                if (permissionType == PermissionType.USER || permissionType == null) {
                    node.userPermissions.removeIf { key, value -> value.entityId == entityId }
                        ?.also { permissionIndex_remove(it) }
                }
                if (permissionType == PermissionType.ROLE || permissionType == null) {
                    node.rolePermissions.removeIf { key, value -> value.entityId == entityId }
                        ?.also { permissionIndex_remove(it) }
                }
            }
        }
    }

    /**
     * Moves or renames a path in the tree.
     * Returns true if successful, false if source doesn't exist or target already exists.
     */
    fun movePath(oldPath: String, newPath: String): Boolean {
        treeLock.write {
            // locate source node
            val node = findNode(oldPath, getClosestNode = false)
                ?: return false

            // fail if target already exists
            if (findNode(newPath, getClosestNode = false) != null) return false

            // split newPath into parent path and new name
            val trimmed = newPath.trim('/')
            val segments = if (trimmed.isBlank()) emptyList() else trimmed.split('/')
            val newName = segments.lastOrNull().orEmpty()
            val parentPath = "/" + segments.dropLast(1).joinToString("/")

            // get or create the new parent node
            val newParent = if (parentPath == "/") root else getOrCreateTreePath(parentPath)

            // Prevent creating a cycle: do not move a node into its own subtree.
            // Walk up from newParent to root; if we encounter `node`, newParent is
            // a descendant of `node` and the move would create a cycle.
            var current: Node? = newParent
            while (current != null) {
                if (current === node) return false
                current = current.parent
            }

            // detach from old parent (if any)
            node.parent?.children?.remove(node.segment)

            // reassign node name and parent, then attach to new parent
            node.segment = newName
            node.parent = newParent
            newParent.children[newName] = node

            return true
        }
    }

    /**
     * Gets all permissions directly assigned to a path (non-inherited).
     */
    fun getAllPermissionsForPath(path: String): List<EntityPermission> {
        val node = findNode(path, getClosestNode = false) ?: return emptyList()
        return collectAllPermissions(node)
    }

    /**
     * Collects all user and role permissions from a node.
     */
    private fun collectAllPermissions(node: Node): List<EntityPermission> {
        val results = mutableListOf<EntityPermission>()
        treeLock.read {
            results.addAll(node.userPermissions.values)
            results.addAll(node.rolePermissions.values)
        }

        return results
    }

    /**
     * Gets the permission directly assigned to a path for a user (non-inherited).
     */
    fun getDirectPermissionForUser(path: String, userId: Ulid): EntityPermission? {
        treeLock.read {
            val node = findNode(path, getClosestNode = false) ?: return null
            return node.userPermissions[userId]
        }
    }

    /**
     * Gets the permission directly assigned to a path for a role (non-inherited).
     */
    fun getDirectPermissionForRole(path: String, roleId: Ulid): EntityPermission? {
        treeLock.read {
            val node = findNode(path, getClosestNode = false) ?: return null
            return node.rolePermissions[roleId]
        }
    }

    /**
     * Finds a permission by its ID anywhere in the tree.
     */
    fun getPermissionById(permissionId: Ulid): EntityPermission? {
        return findPermissionById(root, permissionId)
    }

    /**
     * Recursively searches for a permission by ID in the tree.
     */
    private fun findPermissionById(node: Node, permissionId: Ulid, isFirst: Boolean = true): EntityPermission? {
        if (isFirst) treeLock.readLock().lock()

        try {
            // Check userPermissions
            node.userPermissions.values.firstOrNull { it.permissionId == permissionId }?.let { return it }
            // Check rolePermissions
            node.rolePermissions.values.firstOrNull { it.permissionId == permissionId }?.let { return it }

            // Recurse into children
            for (child in node.children.values) {
                findPermissionById(child, permissionId, isFirst = false)?.let { return it }
            }

            return null
        } finally {
            if (isFirst) treeLock.readLock().unlock()
        }
    }

    /**
     * Removes a permission by permissionId from the entire tree.
     * Returns true if a matching permission was found and removed; false if not.
     */
    fun removePermissionByPermissionId(permissionId: Ulid): Boolean {
        return removePermissionByPermissionIdRecursive(root, permissionId)
    }

    /**
     * Recursively removes a permission by ID from the tree.
     * Returns true if found and removed.
     */
    private fun removePermissionByPermissionIdRecursive(node: Node, permissionId: Ulid, isFirst: Boolean = true): Boolean {
        if (isFirst) {
            treeLock.writeLock().lock()
            permissionsIndexLock.writeLock().lock()
        }

        try {
            // Try removing in userPermissions
            val userKey = node.userPermissions.entries.find { it.value.permissionId == permissionId }?.key
            if (userKey != null) {
                node.userPermissions.remove(userKey)
                    ?.also { permissionIndex_remove(it)  }

                return true
            }

            // Try removing in rolePermissions
            val roleKey = node.rolePermissions.entries.find { it.value.permissionId == permissionId }?.key
            if (roleKey != null) {
                node.rolePermissions.remove(roleKey)
                    ?.also { permissionIndex_remove(it) }

                return true
            }

            // Recurse into children
            for (child in node.children.values) {
                if (removePermissionByPermissionIdRecursive(child, permissionId)) {
                    return true
                }
            }
            return false
        } finally {
            if (isFirst) {
                treeLock.writeLock().unlock()
                permissionsIndexLock.writeLock().unlock()
            }
        }
    }

    /**
     * Gets all entities that a user has access to.
     * Returns only top-level entities and child entities where there are gaps in parent permissions.
     *
     * For example, if user has access to `/folder`, no access to `/folder/subfolder`,
     * but access to `/folder/subfolder/file`, returns permissions for both `/folder` and `/folder/subfolder/file`.
     */
    fun getAllAccessibleEntitiesForUser(userId: Ulid, roleIds: List<Ulid>): List<EntityPermission> {
        val result = mutableListOf<EntityPermission>()

        treeLock.read {
            traverseAndCollectTopLevelAccessible(root, userId, roleIds, result)
        }

        return result
    }

    /**
     * Recursively traverses the tree and collects top-level accessible entities for the user.
     */
    private fun traverseAndCollectTopLevelAccessible(
        node: Node,
        userId: Ulid,
        roleIds: List<Ulid>,
        result: MutableList<EntityPermission>
    ) {
        // Check if user has permission for this node
        val userPermission = node.userPermissions[userId]
        val hasReadPermissionForUser = userPermission?.let { FilePermission.READ in userPermission.permissions }

        // Only load role permissions if user hasn't denied READ permission
        val rolePermissions = let {
            if (hasReadPermissionForUser != false) {
                roleIds.mapNotNull { roleId ->
                    node.rolePermissions[roleId]
                        ?.takeIf { FilePermission.READ in it.permissions }
                }
            } else null
        }

        val hasAccess = (hasReadPermissionForUser == true) || (rolePermissions?.isNotEmpty() ?: false)

        if (hasAccess) {
            // Check if this should be included (is "top-level" in accessible area)
            if (isTopLevelAccessible(node, userId, roleIds)) {
                // Add the permissions that grant access
                if (hasReadPermissionForUser == true) {
                    userPermission.let { result.add(it) }
                }
                rolePermissions?.let { result.addAll(rolePermissions) }
            }
        }

        // Always recurse into children to find deeper accessible entities
        for (child in node.children.values) {
            traverseAndCollectTopLevelAccessible(child, userId, roleIds, result)
        }
    }

    /**
     * Checks if a node is a top-level accessible entity (parent has no access).
     */
    private fun isTopLevelAccessible(node: Node, userId: Ulid, roleIds: List<Ulid>): Boolean {
        // Root is always top-level if accessible
        if (node.parent == null) return true

        // Check immediate parent - if user doesn't have access to it, this node is top-level
        val parent = node.parent!!
        val parentHasUserPermission = parent.userPermissions[userId]?.permissions?.contains(FilePermission.READ) ?: false
        val parentHasRolePermission = roleIds.any { roleId -> parent.rolePermissions[roleId]?.permissions?.contains(FilePermission.READ) ?: false }

        return !parentHasUserPermission && !parentHasRolePermission
    }

    /**
     * Resolves the effective permissions for a user at a specific path.
     *
     * Specificity Rules:
     * 1. Traverses from the target node upwards (Specificity by Depth).
     * 2. At each level, User permissions take precedence over Role permissions (Specificity by Type).
     * 3. Multiple Role permissions at the same level are merged (Union).
     */
    fun resolveEffectivePermissions(path: String, userId: Ulid, roleIds: List<Ulid>): Set<FilePermission> {
        return treeLock.read {
            var current: Node? = findNode(path, getClosestNode = true)
            while (current != null) {
                val node = current // Captured stable reference for closure/lambda

                // Check for User permission at this specific level
                val userPerm = node.userPermissions[userId]
                if (userPerm != null) {
                    return@read userPerm.permissions.toSet()
                }

                // If no User permission, check for Role permissions at this level
                val applicableRolePerms = roleIds.mapNotNull { roleId ->
                    node.rolePermissions[roleId]
                }
                if (applicableRolePerms.isNotEmpty()) {
                    // Merge permissions from all matching roles at this level
                    return@read applicableRolePerms.flatMap { it.permissions }.toSet()
                }

                // Move to parent to find inherited permissions
                current = node.parent
            }
            emptySet()
        }
    }
}
