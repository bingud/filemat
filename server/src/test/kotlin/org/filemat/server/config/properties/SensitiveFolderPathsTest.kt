package org.filemat.server.config.properties

import org.filemat.server.config.platform.WindowsSystemFolders
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The Windows list is built from given folders, so these tests run on every OS.
 */
class SensitiveFolderPathsTest {

    private val folders = WindowsSystemFolders.of(
        env = mapOf(
            "SystemDrive" to "C:",
            "SystemRoot" to "C:\\Windows",
            "ProgramData" to "C:\\ProgramData",
            "ProgramFiles" to "C:\\Program Files",
        ),
        profiles = "C:\\Users",
    )
    private val windows = SensitiveFolderPaths.toSubtreeRegexList(SensitiveFolderPaths.windowsList(folders), ignoreCase = true)

    private fun blocked(path: String) = windows.any { it.matches(path) }

    @Test
    fun `registry hives are blocked in any case`() {
        assertTrue(blocked("C:/Windows/System32/config/SAM"))
        assertTrue(blocked("c:/windows/system32/config/sam"))
        assertTrue(blocked("C:/Windows/System32/config/systemprofile/AppData"))
        assertTrue(blocked("C:/Users/bob/NTUSER.DAT"))
        assertTrue(blocked("C:/Users/bob/ntuser.dat.LOG1"))
    }

    @Test
    fun `only the credential parts of AppData are blocked`() {
        assertTrue(blocked("C:/Users/bob/AppData/Roaming/Microsoft/Credentials/abc"))
        assertTrue(blocked("C:/Users/bob/AppData/Local/Microsoft/Vault"))
        assertTrue(blocked("C:/Users/bob/AppData/Local/Google/Chrome/User Data/Default/Login Data"))
        assertTrue(blocked("C:/Users/bob/AppData/Roaming/Mozilla/Firefox/Profiles/x.default"))
        assertFalse(blocked("C:/Users/bob/AppData"))
        assertFalse(blocked("C:/Users/bob/AppData/Local/Temp/a.txt"))
        assertFalse(blocked("C:/Users/bob/AppData/Roaming/Microsoft/Windows/Start Menu"))
    }

    @Test
    fun `other browsers, mail, sign-in tokens and shell history are blocked in any case`() {
        listOf(
            "C:/Users/bob/AppData/Local/BraveSoftware/Brave-Browser/User Data/Default/Login Data",
            "C:/Users/bob/AppData/Local/Chromium/User Data",
            "C:/Users/bob/AppData/Local/Vivaldi/User Data/Default/Cookies",
            "C:/Users/bob/AppData/Roaming/Opera Software/Opera Stable/Login Data",
            "C:/Users/bob/AppData/Roaming/Thunderbird/Profiles/x.default/logins.json",
            "C:/Users/bob/AppData/Local/Microsoft/TokenBroker/Cache",
            "C:/Users/bob/AppData/Local/Microsoft/IdentityCache",
            "C:/Users/bob/AppData/Roaming/Microsoft/Windows/PowerShell/PSReadLine/ConsoleHost_history.txt",
            "c:/users/bob/appdata/local/bravesoftware",
            "C:/USERS/BOB/APPDATA/ROAMING/MICROSOFT/WINDOWS/POWERSHELL/PSREADLINE",
        ).forEach { assertTrue(blocked(it), it) }
        assertFalse(blocked("C:/Users/bob/AppData/Roaming/Thunderbird/installs.ini"))
        assertFalse(blocked("C:/Users/bob/AppData/Roaming/Microsoft/Windows/PowerShell/profile.ps1"))
    }

    @Test
    fun `saved passwords of Git and other tools are blocked in each profile`() {
        assertTrue(blocked("C:/Users/bob/.git-credentials"))
        assertTrue(blocked("C:/Users/bob/_netrc"))
        assertTrue(blocked("C:/Users/bob/.NETRC"))
        assertFalse(blocked("C:/Users/bob/projects/.git-credentials"))
        assertFalse(blocked("C:/Users/bob/.netrc.example"))
    }

    @Test
    fun `the Offline Files cache in the Windows folder is blocked`() {
        assertTrue(blocked("C:/Windows/CSC/v2.0.6/namespace"))
        assertTrue(blocked("c:/windows/csc"))
        assertFalse(blocked("C:/Windows/CSCx"))
        assertFalse(blocked("D:/CSC"))
    }

    @Test
    fun `system files are blocked at the root of every drive`() {
        assertTrue(blocked("D:/\$Recycle.Bin/S-1-5-21/file.txt"))
        assertTrue(blocked("E:/System Volume Information"))
        assertTrue(blocked("C:/pagefile.sys"))
        assertTrue(blocked("C:/DumpStack.log.tmp"))
        assertFalse(blocked("D:/Photos/pagefile.sys"))
    }

    @Test
    fun `a star matches one folder`() {
        assertTrue(blocked("C:/Users/bob/.ssh/id_ed25519"))
        assertTrue(blocked("C:/Program Files/PostgreSQL/16/data/pg_hba.conf"))
        assertFalse(blocked("C:/Users/bob/projects/.ssh"))
        assertFalse(blocked("C:/Program Files/PostgreSQL/16/bin"))
    }

    @Test
    fun `names only match whole segments`() {
        assertFalse(blocked("C:/Users/bob/.sshkeys"))
        assertFalse(blocked("C:/Windows/System32/configuration"))
    }

    @Test
    fun `ordinary files are not blocked`() {
        assertFalse(blocked("C:/Users/bob/Documents/report.docx"))
        assertFalse(blocked("C:/Windows/System32/drivers"))
        assertFalse(blocked("D:/Backups"))
    }

    @Test
    fun `folders come from the environment, not from C`() {
        val other = WindowsSystemFolders.of(mapOf("SystemDrive" to "D:", "SystemRoot" to "D:\\WINNT"), profiles = "E:\\Profiles")
        val list = SensitiveFolderPaths.toSubtreeRegexList(SensitiveFolderPaths.windowsList(other), ignoreCase = true)

        assertTrue(list.any { it.matches("D:/WINNT/System32/config/SAM") })
        assertTrue(list.any { it.matches("E:/Profiles/bob/.ssh") })
        assertFalse(list.any { it.matches("C:/Windows/System32/config/SAM") })
    }

    @Test
    fun `Linux patterns keep their case`() {
        val posix = SensitiveFolderPaths.toSubtreeRegexList(listOf("/home/*/.ssh"), ignoreCase = false)

        assertTrue(posix.any { it.matches("/home/bob/.ssh/id") })
        assertFalse(posix.any { it.matches("/home/bob/.SSH") })
    }
}
