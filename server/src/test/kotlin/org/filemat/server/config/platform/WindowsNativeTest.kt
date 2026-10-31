package org.filemat.server.config.platform

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Calls the real Windows API through JNA, so it checks the native signatures too.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsNativeTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `current account is DOMAIN backslash user name`() {
        val account = WindowsNative.currentAccountName()

        assertNotNull(account)
        assertTrue(account!!.contains('\\'), account)
        assertEquals(System.getProperty("user.name").lowercase(), account.substringAfter('\\').lowercase())
    }

    @Test
    fun `well-known SIDs resolve to an account name`() {
        assertNotNull(WindowsNative.accountNameOfSid(WindowsNative.systemSid))
        assertNotNull(WindowsNative.accountNameOfSid(WindowsNative.administratorsSid))
    }

    @Test
    fun `an unknown or malformed SID returns null`() {
        assertNull(WindowsNative.accountNameOfSid("S-1-5-21-1-2-3-999999"))
        assertNull(WindowsNative.accountNameOfSid("not a sid"))
    }

    private fun whoami(option: String): String {
        val process = ProcessBuilder("whoami", option).redirectErrorStream(true).start()
        val output = String(process.inputStream.readAllBytes())
        process.waitFor()
        return output
    }

    @Test
    fun `elevation check agrees with whoami`() {
        // High (S-1-16-12288) and system (S-1-16-16384) integrity levels are in the groups, LocalSystem is the user
        val groups = whoami("/groups")
        val expected = "S-1-16-12288" in groups || "S-1-16-16384" in groups || "S-1-5-18" in whoami("/user")

        assertEquals(expected, WindowsNative.isElevatedOrSystem())
    }

    @Test
    fun `Windows calls work in a normal run`() {
        assertNull(WindowsNative.unavailableReason)
    }

    @Test
    fun `a local folder is not on a network drive`() {
        assertEquals(false, WindowsNative.isOnNetworkDrive(tempDir))
    }

    @Test
    fun `a path without a root returns null`() {
        assertNull(WindowsNative.isOnNetworkDrive(Paths.get("relative")))
    }

    @Test
    fun `an ordinary drive is not a subst drive`() {
        assertNull(WindowsNative.substTarget(WindowsSystemFolders.current.systemDrive))
        assertNull(WindowsNative.substTarget("not a drive"))
    }

    @Test
    fun `a process can be run at below normal priority`() {
        val process = ProcessBuilder("ping", "-n", "5", "127.0.0.1").redirectErrorStream(true).start()
        try {
            assertEquals(true, WindowsNative.lowerPriority(process.pid()))

            val handle = Kernel32.INSTANCE.OpenProcess(WinNT.PROCESS_QUERY_LIMITED_INFORMATION, false, process.pid().toInt())
            try {
                assertEquals(0x4000L, Kernel32.INSTANCE.GetPriorityClass(handle).toLong())
            } finally {
                Kernel32.INSTANCE.CloseHandle(handle)
            }
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `a process that does not exist keeps no priority change`() {
        assertEquals(false, WindowsNative.lowerPriority(0x7FFFFFF0))
    }

    @Test
    fun `a missing path has no reparse tag`() {
        assertNull(WindowsNative.reparseTag(tempDir.resolve("missing")))
        assertEquals(0, WindowsNative.reparseTag(Files.writeString(tempDir.resolve("file.txt"), "x")))
    }
}
