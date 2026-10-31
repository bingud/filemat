package org.filemat.server.module.file.model

import org.filemat.server.config.platform.PathPolicy

/**
 * Visibility rules by path. Every function takes path keys (`PathPolicy.pathKey`), so matching follows the case rules of each folder.
 */
class VisibilityTrie {
    private val root = TrieNode()

    data class Visibility(
        val isExposed: Boolean,
        /**
         * Whether the file visibility was set explicitly (or otherwise inherited)
         */
        val isExplicit: Boolean
    )


    /**
     * @param path Path as entered, returned by [getAllVisibilities]
     */
    fun insert(key: String, isExposed: Boolean, path: String = key) {
        var node = root
        val parts = PathPolicy.segments(key)
        for (part in parts) {
            node = node.children.getOrPut(part) { TrieNode() }
        }
        node.isExposed = isExposed
        node.hasRule = true
        node.path = path
    }

    /**
     * Resolve the visibility for a given path.
     * - If no explicit rule exists on the path, the closest ancestor’s rule is inherited.
     * - `isExplicit = true` if the rule is defined exactly at this path (otherwise the visibility is inherited)
     */
    fun getVisibility(key: String): Visibility {
        var node = root
        // If the root itself has a rule, pick it up immediately.
        var lastKnownVisibility = if (root.hasRule) root.isExposed else null
        var lastKnownVisibilityIndex = if (root.hasRule) -1 else null

        val parts = PathPolicy.segments(key)
        parts.forEachIndexed { index, part ->
            // Update from the current node’s rule before moving on
            // (We already did this for the root outside the loop.)

            // The path has no node, so its visibility can only be inherited
            val child = node.children[part] ?: return Visibility(
                isExposed = lastKnownVisibility ?: false,
                isExplicit = false
            )
            node = child

            if (node.hasRule) {
                lastKnownVisibility = node.isExposed
                lastKnownVisibilityIndex = index
            }
        }

        return Visibility(
            isExposed = lastKnownVisibility ?: false,
            isExplicit = (lastKnownVisibilityIndex == parts.lastIndex)
        )
    }

    /**
     * Returns a map of all path visibilities
     *
     * Path + isExposed
     */
    fun getAllVisibilities(): Map<String, Boolean> {
        val result = mutableMapOf<String, Boolean>()

        fun dfs(node: TrieNode, path: List<String>) {
            if (node.hasRule) {
                val fullPath = node.path ?: PathPolicy.join(path)
                result[fullPath] = node.isExposed
            }
            for ((part, child) in node.children) {
                dfs(child, path + part)
            }
        }

        dfs(root, emptyList())
        return result
    }

    fun hasExplicitRule(key: String): Boolean {
        var node = root
        val parts = PathPolicy.segments(key)
        for (part in parts) {
            node = node.children[part] ?: return false
        }
        return node.hasRule
    }

    fun remove(key: String) {
        fun dfs(node: TrieNode, parts: List<String>, depth: Int): Boolean {
            if (depth == parts.size) {
                if (!node.hasRule) return false
                node.hasRule = false
                node.path = null
                return node.children.isEmpty()
            }

            val part = parts[depth]
            val child = node.children[part] ?: return false
            val shouldDeleteChild = dfs(child, parts, depth + 1)

            if (shouldDeleteChild) {
                node.children.remove(part)
            }

            return !node.hasRule && node.children.isEmpty()
        }

        val parts = PathPolicy.segments(key)
        dfs(root, parts, 0)
    }


    private class TrieNode {
        val children = mutableMapOf<String, TrieNode>()
        var isExposed: Boolean = false
        var hasRule: Boolean = false
        var path: String? = null
    }
}