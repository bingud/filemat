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
        var lastKnownVisibility: Boolean? = null
        var lastKnownVisibilityIndex: Int? = null

        val parts = path.split("/").filter { it.isNotEmpty() }
        parts.forEachIndexed { index, part ->
            node = node.children[part] ?: return Visibility(
                isExposed = lastKnownVisibility ?: false,
                isExplicit = lastKnownVisibilityIndex == index
            )
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
