package org.filemat.server.module.file.model

class VisibilityTrie {
    private val root = TrieNode()

    data class Visibility(val isExposed: Boolean, val isExplicit: Boolean)

    fun insert(path: String, isExposed: Boolean) {
        var node = root
        val parts = path.split("/").filter { it.isNotEmpty() }
        for (part in parts) {
            node = node.children.getOrPut(part) { TrieNode() }
        }
        node.isExposed = isExposed
        node.hasRule = true
    }

    fun getVisibility(path: String): Visibility {
        var node = root
        // If the root itself has a rule, pick it up immediately.
        var lastKnownVisibility = if (root.hasRule) root.isExposed else null
        var lastKnownVisibilityIndex = if (root.hasRule) -1 else null

        val parts = path.split("/").filter { it.isNotEmpty() }
        parts.forEachIndexed { index, part ->
            // Update from the current node’s rule before moving on
            // (We already did this for the root outside the loop.)

            val child = node.children[part] ?: return Visibility(
                isExposed = lastKnownVisibility ?: false,
                isExplicit = (lastKnownVisibilityIndex == index - 1)
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

    private class TrieNode {
        val children = mutableMapOf<String, TrieNode>()
        var isExposed: Boolean = false
        var hasRule: Boolean = false
    }
}