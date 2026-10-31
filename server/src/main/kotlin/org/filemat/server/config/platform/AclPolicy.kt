package org.filemat.server.config.platform

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal

/**
 * Restricts access to Filemat's own files (data folder, database, setup and auth codes).
 */
object AclPolicy {

    /**
     * Makes a file or folder private to the user running Filemat. Does nothing when it already is, so it can run on every start.
     *
     * POSIX: sets [posixPermissions] (for example `rw-------`), unless the current ones grant no more.
     * Windows: replaces the ACL with full control for the current user, SYSTEM and Administrators, unless it names no one else.
     * A folder's ACL is then protected from its parent's, and its entries replace the inherited ones of everything inside.
     *
     * Throws when the permissions cannot be set.
     */
    fun restrictToOwner(path: Path, posixPermissions: String) {
        if (Platform.isWindows) {
            restrictWindowsAcl(path)
        } else {
            if (isRestricted(path, posixPermissions)) return
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(posixPermissions))
        }
    }

    /**
     * Whether [restrictToOwner] would leave [path] as it is: the permissions grant no more than [posixPermissions],
     * or the ACL names no one but the current user, SYSTEM and Administrators.
     *
     * Throws when the permissions cannot be read.
     */
    fun isRestricted(path: Path, posixPermissions: String): Boolean {
        if (Platform.isWindows) return isWindowsAclRestricted(path)
        return PosixFilePermissions.fromString(posixPermissions).containsAll(Files.getPosixFilePermissions(path))
    }

    /**
     * Creates a new private file.
     *
     * POSIX: created with [posixPermissions], or the default permissions when null.
     * Windows: created, then restricted with [restrictToOwner]. A failed restriction is only printed.
     */
    fun createPrivateFile(path: Path, posixPermissions: String?) {
        if (Platform.isWindows) {
            Files.createFile(path)
            runCatching { restrictWindowsAcl(path) }.onFailure {
                println("[!] Failed to restrict access to $path: ${it.message}")
            }
        } else if (posixPermissions != null) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(posixPermissions)))
        } else {
            Files.createFile(path)
        }
    }

    private fun restrictWindowsAcl(path: Path) {
        // Already private, for example the default data folder, whose entries the user's profile passes down
        if (isWindowsAclRestricted(path)) return

        WindowsNative.setOwnerOnlyDacl(path, isFolder = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
            ?: throw IllegalStateException("Could not restrict access to $path.")
    }

    private fun isWindowsAclRestricted(path: Path): Boolean {
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?: throw UnsupportedOperationException("The filesystem of $path does not support ACLs.")

        val acl = view.acl
        // The JDK reads a NULL DACL, which gives everyone full access, as an empty list
        if (acl.isEmpty()) return false
        val owners = windowsOwners(path)
        return acl.all { it.principal() in owners }
    }

    /**
     * Current user (required), SYSTEM and Administrators (skipped when they cannot be looked up)
     */
    private fun windowsOwners(path: Path): List<UserPrincipal> {
        val lookup = path.fileSystem.userPrincipalLookupService
        // Not `user.name`, which could name a domain account with the same name
        val currentUser = WindowsNative.currentAccountName() ?: throw IllegalStateException("The current account could not be read.")

        val others = listOf(WindowsNative.systemSid, WindowsNative.administratorsSid).mapNotNull { sid ->
            val name = WindowsNative.accountNameOfSid(sid) ?: return@mapNotNull null
            runCatching { lookup.lookupPrincipalByName(name) }.getOrNull()
        }

        return (listOf(lookup.lookupPrincipalByName(currentUser)) + others).distinct()
    }
}
