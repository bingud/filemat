package org.filemat.server.config.platform

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinNT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumSet

class AclPolicyTest {

    @TempDir
    lateinit var tempDir: Path

    @Nested
    @DisabledOnOs(OS.WINDOWS)
    inner class Posix {

        @Test
        fun `restrictToOwner sets the given permissions`() {
            val folder = Files.createDirectory(tempDir.resolve("data"))

            AclPolicy.restrictToOwner(folder, "rwxr-x---")

            assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(folder)))
        }

        @Test
        fun `isRestricted is true only when the permissions grant no more than the given ones`() {
            val file = Files.createFile(tempDir.resolve("filemat-server.db"))
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))

            assertTrue(AclPolicy.isRestricted(file, "rw-------"))
            assertTrue(AclPolicy.isRestricted(file, "rw-r-----"))

            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))
            assertFalse(AclPolicy.isRestricted(file, "rw-------"))
        }

        @Test
        fun `createPrivateFile creates the file with the given permissions`() {
            val file = tempDir.resolve("setup-code.txt")

            AclPolicy.createPrivateFile(file, "rw-------")

            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        }

        @Test
        fun `createPrivateFile without permissions creates a plain file`() {
            val file = tempDir.resolve("auth-code.txt")

            AclPolicy.createPrivateFile(file, null)

            assertTrue(Files.isRegularFile(file))
        }
    }

    @Nested
    @EnabledOnOs(OS.WINDOWS)
    inner class Windows {

        private val ownerNames: Set<String> by lazy {
            val lookup = tempDir.fileSystem.userPrincipalLookupService
            val names = listOfNotNull(
                WindowsNative.currentAccountName(),
                WindowsNative.accountNameOfSid(WindowsNative.systemSid),
                WindowsNative.accountNameOfSid(WindowsNative.administratorsSid),
            )
            names.map { lookup.lookupPrincipalByName(it).name }.toSet()
        }

        private fun aclOf(path: Path): List<AclEntry> =
            Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).acl

        private val usersName: String by lazy { WindowsNative.accountNameOfSid("S-1-5-32-545") ?: error("BUILTIN\\Users not found") }

        private fun principals(path: Path) = aclOf(path).map { it.principal().name }.toSet()

        private fun isDaclProtected(path: Path) =
            (Advapi32Util.getFileSecurityDescriptor(path.toFile(), false).Control.toInt() and WinNT.SE_DACL_PROTECTED) != 0

        /** Adds a read entry for BUILTIN\Users, as a folder under a shared parent would have. */
        private fun grantUsers(path: Path, flags: Set<AclEntryFlag> = emptySet()) {
            val users = path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(usersName)
            val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            view.acl = view.acl + AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(users)
                .setPermissions(EnumSet.of(AclEntryPermission.READ_DATA))
                .setFlags(flags)
                .build()
        }

        @Test
        fun `restrictToOwner under a parent that grants Users removes them from the folder and what is inside, for good`() {
            val parent = Files.createDirectory(tempDir.resolve("shared"))
            grantUsers(parent, setOf(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT))
            val folder = Files.createDirectory(parent.resolve("data"))
            val log = Files.writeString(Files.createDirectory(folder.resolve("logs")).resolve("filemat.log"), "x")
            assertTrue(usersName in principals(log), "inherited before")

            AclPolicy.restrictToOwner(folder, "rwxr-x---")

            assertEquals(ownerNames, principals(folder))
            assertEquals(ownerNames, principals(log))
            assertTrue(isDaclProtected(folder))

            // A later change of the parent, which Windows passes on to its children, no longer reaches the folder
            val granted = ProcessBuilder("icacls", parent.toString(), "/grant", "*S-1-5-32-545:(OI)(CI)R", "/Q")
                .redirectErrorStream(true).start().waitFor()
            assertEquals(0, granted)
            assertEquals(ownerNames, principals(folder))
        }

        @Test
        fun `restrictToOwner leaves only the user, SYSTEM and Administrators on a folder`() {
            val folder = Files.createDirectory(tempDir.resolve("data"))
            grantUsers(folder)

            AclPolicy.restrictToOwner(folder, "rwxr-x---")

            val acl = aclOf(folder)
            assertEquals(ownerNames, acl.map { it.principal().name }.toSet())
            acl.forEach { entry ->
                assertEquals(AclEntryType.ALLOW, entry.type())
                assertEquals(EnumSet.allOf(AclEntryPermission::class.java), entry.permissions())
                assertEquals(setOf(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT), entry.flags())
            }
        }

        @Test
        fun `an empty ACL is not private, and restrictToOwner replaces it`() {
            val folder = Files.createDirectory(tempDir.resolve("data"))
            Files.getFileAttributeView(folder, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).acl = emptyList()

            assertFalse(AclPolicy.isRestricted(folder, "rwxr-x---"))
            AclPolicy.restrictToOwner(folder, "rwxr-x---")

            assertEquals(ownerNames, principals(folder))
            assertTrue(AclPolicy.isRestricted(folder, "rwxr-x---"))
        }

        @Test
        fun `restrictToOwner on a file sets no inheritance flags`() {
            val file = Files.createFile(tempDir.resolve("filemat-server.db"))
            grantUsers(file)

            AclPolicy.restrictToOwner(file, "rw-------")

            val acl = aclOf(file)
            assertEquals(ownerNames, acl.map { it.principal().name }.toSet())
            acl.forEach { assertTrue(it.flags().isEmpty(), "flags of ${it.principal().name}") }
        }

        @Test
        fun `createPrivateFile creates a file restricted to the owners`() {
            val file = tempDir.resolve("auth-code.txt")

            AclPolicy.createPrivateFile(file, null)

            assertTrue(Files.isRegularFile(file))
            assertEquals(ownerNames, aclOf(file).map { it.principal().name }.toSet())
        }
    }

    @Test
    fun `createPrivateFile fails when the file exists`() {
        val file = Files.createFile(tempDir.resolve("exists.txt"))

        assertThrows<FileAlreadyExistsException> { AclPolicy.createPrivateFile(file, "rw-------") }
    }
}
