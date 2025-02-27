package org.filemat.server.config.properties

import org.filemat.server.common.util.normalizePath


object SensitiveFolderPaths {

    /**
     * List of sensitive folders.
     *
     * Contains stars as wildcards.
     */
    private val fullList = setOf(
        "/etc/shadow",
        "/etc/passwd",
        "/etc/sudoers",
        "/etc/ssh",
        "/etc/hostname",
        "/etc/hosts",
        "/etc/group",
        "/etc/gshadow",
        "/root",
        "/home/*/.ssh",
        "/home/*/.gnupg",
        "/home/*/.bash_history",
        "/home/*/.zsh_history",
        "/home/*/.config/google-chrome",
        "/home/*/.mozilla/firefox",
        "/var/spool/cron",
        "/var/spool/at",
        "/var/log",
        "/var/lib/mysql",
        "/var/lib/postgresql",
        "/var/lib/docker",
        "/var/lib/kubelet",
        "/var/lib/etcd",
        "/var/www",
        "/proc/kcore",
        "/proc/self",
        "/proc/1",
        "/sys/kernel/security",
        "/boot",
        "/dev/mem",
        "/dev/kmem",
        "/dev/sd",
        "/var/lib/filemat",
    )

    private val wildcardList = fullList.filter { it.contains("*") }.map { (if (it.startsWith("/")) it else "/$it").split("*") }.map { it[0] to it[1] }
    private val list = fullList.filterNot { it.contains("*") }.toHashSet()

    fun contains(_path: String, isPathNormalized: Boolean): Boolean {
        val path = if (isPathNormalized) _path else normalizePath(_path)
        if (list.any { path.startsWith(it) }) return true

        wildcardList.forEach { pair ->
            if (path.startsWith(pair.first) && path.endsWith(pair.second)) return true
        }
        return false
    }

}