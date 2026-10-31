package org.filemat.server.common.util

import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.nio.file.FileSystems
import org.filemat.server.config.platform.PathPolicy
import org.filemat.server.config.platform.WindowsNative
import org.filemat.server.config.platform.WindowsSystemFolders
import org.filemat.server.config.properties.SensitiveFolderPaths
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.testutil.assertNotFound
import org.filemat.server.testutil.assertSuccess
import org.filemat.server.testutil.assumeWindowsFeature
import org.filemat.server.testutil.createCaseSensitiveFolder
import org.filemat.server.testutil.junctionOrSkip
import org.filemat.server.testutil.symlinkOrSkip
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Paths that try to reach a blocked file through another name (§10.2). Each one must be rejected or resolve to the real name.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsPathSecurityTest {

    @TempDir
    lateinit var tempDir: Path

    private fun shortName(path: Path): String? {
        val buffer = CharArray(32_768)
        val length = Kernel32.INSTANCE.GetShortPathName(path.toString(), buffer, buffer.size)
        return if (length == 0) null else Native.toString(buffer)
    }

    @Test
    fun `names that Win32 would change are rejected`() {
        for (raw in listOf("C:/Windows/System32/config/SAM.", "C:/Windows/System32/config/SAM ", "C:/Windows/System32/config/SAM::\$DATA")) {
            assertThrows<InvalidPathException>(raw) { PathPolicy.parse(raw) }
        }
    }

    @Test
    fun `device and long-path prefixes are rejected`() {
        for (raw in listOf("\\\\?\\C:\\Windows", "\\\\.\\C:", "//?/C:/Windows")) {
            assertThrows<InvalidPathException>(raw) { PathPolicy.parse(raw) }
        }
    }

    @Test
    fun `UNC paths never reach the network`() {
        // They parse to root-relative paths, which only exist inside a share
        assertNotFound(resolvePath(FilePath.of("//127.0.0.1/c$/Windows"), followSymlinks = true))
        assertNotFound(resolvePath(FilePath.of("\\\\127.0.0.1\\c$\\Windows"), followSymlinks = true, allowNonExistent = true))
    }

    @Test
    fun `the sensitive list ignores case`() {
        val folders = WindowsSystemFolders.current
        val list = SensitiveFolderPaths.toSubtreeRegexList(SensitiveFolderPaths.windowsList(folders), ignoreCase = true)

        assertTrue(list.any { it.matches("${folders.systemRoot.lowercase()}/system32/config/sam") })
    }

    @Test
    fun `an 8_3 short name resolves to the long name or is not found`() {
        val dir = Files.createDirectory(tempDir.resolve("A long folder name"))
        val file = Files.writeString(dir.resolve("a.txt"), "x")
        val short = shortName(dir)
        assumeWindowsFeature(short != null && !short.equals(dir.toString(), ignoreCase = true), "8.3 names are off on this volume")
        val viaShortName = FilePath.ofAlreadyNormalized(Path.of(short!!).resolve("a.txt"))

        assertNotFound(resolvePath(viaShortName, followSymlinks = false))
        val followed = resolvePath(viaShortName, followSymlinks = true)
        assertSuccess(followed)
        assertEquals(file.toRealPath(), followed.value.path)
    }

    @Test
    fun `real names replace 8_3 short names and case without following links`() {
        val real = tempDir.toRealPath()
        val folder = Files.createDirectory(real.resolve("Credentials"))
        val short = shortName(folder)?.let { Path.of(it).fileName.toString() }
        assumeWindowsFeature(short != null && !short.equals("Credentials", ignoreCase = true), "8.3 names are off on this volume")
        val junction = real.resolve("A long junction name")
        junctionOrSkip(junction, folder)

        assertEquals(FilePath.ofAlreadyNormalized(folder), withRealNames(FilePath.ofAlreadyNormalized(real.resolve(short!!))))
        assertEquals(FilePath.ofAlreadyNormalized(folder), withRealNames(FilePath.ofAlreadyNormalized(real.resolve("CREDENTIALS"))))
        // The link itself, not its target
        val junctionShort = shortName(junction)!!.let { Path.of(it).fileName.toString() }
        assertEquals(FilePath.ofAlreadyNormalized(junction), withRealNames(FilePath.ofAlreadyNormalized(real.resolve(junctionShort))))
        // A name that doesn't exist yet stays as it is
        val missing = FilePath.ofAlreadyNormalized(real.resolve("NEW~1"))
        assertEquals(missing, withRealNames(missing))
    }

    @Test
    fun `a path through a junction is not found unless links are followed`() {
        val target = Files.createDirectory(tempDir.resolve("target"))
        val file = Files.writeString(target.resolve("a.txt"), "x")
        val junction = tempDir.resolve("junction")
        junctionOrSkip(junction, target)

        assertNotFound(resolvePath(FilePath.ofAlreadyNormalized(junction.resolve("a.txt")), followSymlinks = false))
        assertNotFound(resolvePath(FilePath.ofAlreadyNormalized(junction.resolve("new.txt")), followSymlinks = false, allowNonExistent = true))
        val followed = resolvePath(FilePath.ofAlreadyNormalized(junction.resolve("a.txt")), followSymlinks = true)
        assertSuccess(followed)
        assertEquals(file.toRealPath(), followed.value.path)
    }

    @Test
    fun `the legacy Application Data junction in a profile is not followed`() {
        val junction = Path.of(System.getProperty("user.home"), "AppData", "Local", "Application Data")
        assumeWindowsFeature(Files.exists(junction, LinkOption.NOFOLLOW_LINKS), "No legacy junction in this profile")

        assertNotFound(resolvePath(FilePath.ofAlreadyNormalized(junction), followSymlinks = false))
    }

    /**
     * Runs [block] with the native drive calls replaced, so drive types can be tested without a network or `subst`
     */
    private fun withDrives(isNetwork: Boolean? = false, substTargets: Map<String, String> = emptyMap(), block: () -> Unit) {
        mockkObject(WindowsNative)
        try {
            every { WindowsNative.isOnNetworkDrive(any()) } returns isNetwork
            every { WindowsNative.substTarget(any()) } answers { substTargets[firstArg<String>().uppercase()] }
            block()
        } finally {
            unmockkObject(WindowsNative)
        }
    }

    @Test
    fun `network drives are not found`() {
        val file = Files.writeString(tempDir.resolve("a.txt"), "x")

        withDrives(isNetwork = true) {
            assertNotFound(resolvePath(FilePath.ofAlreadyNormalized(file), followSymlinks = true))
            assertNotFound(resolvePath(FilePath.ofAlreadyNormalized(tempDir.resolve("new.txt")), followSymlinks = true, allowNonExistent = true))
        }
    }

    @Test
    fun `a drive whose type can't be read is not found`() {
        // JNA failed to load, so a network drive can't be told apart
        val file = Files.writeString(tempDir.resolve("a.txt"), "x")

        withDrives(isNetwork = null) {
            assertNotFound(resolvePath(FilePath.ofAlreadyNormalized(file), followSymlinks = true))
            assertTrue(PathPolicy.virtualRootEntries().isEmpty())
        }
    }

    @Test
    fun `a subst drive resolves to its folder`() {
        val folder = tempDir.toRealPath()
        val file = Files.writeString(folder.resolve("a.txt"), "x")

        withDrives(substTargets = mapOf("Q:" to folder.toString())) {
            val existing = resolvePath(FilePath.of("Q:/a.txt"), followSymlinks = false)
            assertSuccess(existing)
            assertEquals(file.toRealPath(), existing.value.path)

            val missing = resolvePath(FilePath.of("Q:/new/b.txt"), followSymlinks = false, allowNonExistent = true)
            assertSuccess(missing)
            assertEquals(folder.resolve("new").resolve("b.txt"), missing.value.path)
        }
    }

    @Test
    fun `a subst drive on a network folder is not found`() {
        withDrives(substTargets = mapOf("Q:" to "UNC\\server\\share")) {
            assertNotFound(resolvePath(FilePath.of("Q:/a.txt"), followSymlinks = true, allowNonExistent = true))
        }
    }

    @Test
    fun `a subst drive on a network drive is not found`() {
        mockkObject(WindowsNative)
        try {
            // Q: points to a folder on Z:, a mapped network drive
            every { WindowsNative.isOnNetworkDrive(any()) } answers { firstArg<Path>().root.toString().startsWith("Z:", ignoreCase = true) }
            every { WindowsNative.substTarget(any()) } answers { if (firstArg<String>().uppercase() == "Q:") "Z:\\share" else null }

            assertNotFound(resolvePath(FilePath.of("Q:/a.txt"), followSymlinks = true, allowNonExistent = true))
            assertEquals(null, localDrivePath(FilePath.of("Q:/a.txt")))
        } finally {
            unmockkObject(WindowsNative)
        }
    }

    @Test
    fun `a real subst drive points to its folder`() {
        val folder = tempDir.toRealPath()
        val file = Files.writeString(folder.resolve("a.txt"), "x")
        val used = FileSystems.getDefault().rootDirectories.map { it.toString().take(2).uppercase() }.toSet()
        val drive = ('P'..'Y').map { "$it:" }.firstOrNull { it !in used }
        assumeWindowsFeature(drive != null, "No free drive letter")
        val letter = drive!!
        val created = runCatching { ProcessBuilder("subst", letter, folder.toString()).start().waitFor() == 0 }.getOrDefault(false)
        assumeWindowsFeature(created, "subst failed")
        try {
            assertEquals(folder.toString(), WindowsNative.substTarget(letter))
            val result = resolvePath(FilePath.of("$letter/a.txt"), followSymlinks = false)
            assertSuccess(result)
            assertEquals(file.toRealPath(), result.value.path)
        } finally {
            ProcessBuilder("subst", letter, "/D").start().waitFor()
        }
    }

    @Test
    fun `a path without links doesn't reach the network`() {
        val file = Files.writeString(tempDir.resolve("a.txt"), "x")

        assertFalse(reachesNetwork(file))
        assertFalse(reachesNetwork(tempDir.resolve("missing").resolve("b.txt")))
    }

    @Test
    fun `a symbolic link to a network folder is never followed`() {
        // Nothing listens there: creating the link doesn't connect, and following it would
        val network = Path.of("\\\\192.0.2.1\\share")
        val direct = tempDir.resolve("direct")
        symlinkOrSkip(direct, network)
        // A link to a local link that leads to the network
        val chained = tempDir.resolve("chained")
        symlinkOrSkip(chained, direct)

        for (link in listOf(direct, chained)) {
            assertTrue(reachesNetwork(link), "$link")
            assertTrue(reachesNetwork(link.resolve("sub").resolve("file.txt")), "$link")
            assertNotFound(resolvePath(FilePath.ofAlreadyNormalized(link), followSymlinks = true))
            assertNotFound(resolvePath(FilePath.ofAlreadyNormalized(link.resolve("file.txt")), followSymlinks = true, allowNonExistent = true))
        }
    }

    @Test
    fun `a symbolic link to a network drive is never followed`() {
        val used = FileSystems.getDefault().rootDirectories.map { it.toString().take(2).uppercase() }.toSet()
        val drive = ('P'..'Y').map { "$it:" }.firstOrNull { it !in used }
        assumeWindowsFeature(drive != null, "No free drive letter")
        val link = tempDir.resolve("mapped")
        symlinkOrSkip(link, Path.of("$drive\\data"))
        val localRoot = tempDir.root

        mockkObject(WindowsNative)
        try {
            // The free drive letter stands for a mapped network drive
            every { WindowsNative.isOnNetworkDrive(any()) } answers { firstArg<Path>().root != localRoot }
            assertTrue(reachesNetwork(link))
            assertTrue(reachesNetwork(link.resolve("sub").resolve("file.txt")))
            assertNotFound(resolvePath(FilePath.ofAlreadyNormalized(link.resolve("file.txt")), followSymlinks = true, allowNonExistent = true))
        } finally {
            unmockkObject(WindowsNative)
        }
    }

    @Test
    fun `a path that resolves to a network drive is not found`() {
        val target = Files.createDirectory(tempDir.resolve("target")).toRealPath()
        Files.writeString(target.resolve("a.txt"), "x")
        val link = tempDir.resolve("link")
        symlinkOrSkip(link, target)

        mockkObject(WindowsNative)
        try {
            // As if the link led to a network drive that the check before following it missed
            every { WindowsNative.isOnNetworkDrive(any()) } answers { firstArg<Path>().startsWith(target) }
            assertNotFound(resolvePath(FilePath.ofAlreadyNormalized(link.resolve("a.txt")), followSymlinks = true))
            assertNotFound(resolvePath(FilePath.ofAlreadyNormalized(link.resolve("new.txt")), followSymlinks = true, allowNonExistent = true))
        } finally {
            unmockkObject(WindowsNative)
        }
    }

    @Test
    fun `with links off, a junction that differs only in case is not followed`() {
        val parent = createCaseSensitiveFolder(tempDir, "cs")
        val real = Files.createDirectory(parent.resolve("A"))
        Files.writeString(real.resolve("file.txt"), "x")
        val link = parent.resolve("a")
        junctionOrSkip(link, real)

        try {
            for (path in listOf(link, link.resolve("file.txt"))) {
                assertNotFound(resolvePath(FilePath.ofAlreadyNormalized(path), followSymlinks = false))
            }
            assertNotFound(resolvePath(FilePath.ofAlreadyNormalized(link.resolve("new.txt")), followSymlinks = false, allowNonExistent = true))

            // The real folder, and with links on the same link, still open
            assertSuccess(resolvePath(FilePath.ofAlreadyNormalized(real.resolve("file.txt")), followSymlinks = false))
            assertEquals(real.resolve("file.txt"), resolvePath(FilePath.ofAlreadyNormalized(link.resolve("file.txt")), followSymlinks = true).value.path)
        } finally {
            // The temp folder clean-up cannot delete a junction next to its own target
            Files.delete(link)
        }
    }

    @Test
    fun `with links off, a path in the wrong case still opens in a normal folder`() {
        val folder = Files.createDirectory(tempDir.toRealPath().resolve("Normal"))
        val file = Files.writeString(folder.resolve("Report.txt"), "x")

        val result = resolvePath(FilePath.ofAlreadyNormalized(tempDir.toRealPath().resolve("NORMAL").resolve("report.TXT")), followSymlinks = false)

        assertSuccess(result)
        assertEquals(file, result.value.path)
    }

    @Test
    fun `share containment compares case in a case-sensitive folder`() {
        val parent = createCaseSensitiveFolder(tempDir, "cs")
        val share = Files.createDirectory(parent.resolve("Share"))
        val sibling = Files.createDirectory(parent.resolve("SHARE"))

        assertTrue(isPathInside(share.resolve("a.txt"), share))
        assertFalse(isPathInside(sibling.resolve("a.txt"), share))
        // A normal folder ignores case
        val normal = Files.createDirectory(tempDir.resolve("Normal"))
        assertTrue(isPathInside(tempDir.resolve("NORMAL").resolve("a.txt"), normal))
    }
}
