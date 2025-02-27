package org.filemat.server.module.file.model

class VisibilityTrie {
    private val root = TrieNode()

    fun insert(path: String, isExposed: Boolean) {
        var node = root
        val parts = path.split("/").filter { it.isNotEmpty() }
        for (part in parts) {
            node = node.children.getOrPut(part) { TrieNode() }
        }
        node.isExposed = isExposed
        node.hasRule = true
    }

    fun getVisibility(path: String): Boolean {
        var node = root
        var lastKnownVisibility: Boolean? = null

        val parts = path.split("/").filter { it.isNotEmpty() }
        for (part in parts) {
            node = node.children[part] ?: return (lastKnownVisibility ?: false)
            if (node.hasRule) lastKnownVisibility = node.isExposed
        }
        return lastKnownVisibility ?: false
    }

    private class TrieNode {
        val children = mutableMapOf<String, TrieNode>()
        var isExposed: Boolean = false
        var hasRule: Boolean = false
    }
}
