package org.filemat.server.testutil

import java.nio.file.Path

/**
 * Runs [block] while the ACL of a Windows file or folder denies the current account reading it, like
 * `icacls <path> /deny <you>:(R)`. Skips the test when icacls can't set it. The deny entry is removed afterwards,
 * or the temp folder cleanup could not delete the file.
 */
fun <T> withReadDenied(path: Path, block: () -> T): T {
    // The SID of the account, so a name with spaces or a domain needs no quoting
    val user = "*" + com.sun.jna.platform.win32.Advapi32Util.getAccountByName(System.getProperty("user.name")).sidString
    assumeWindowsFeature(icacls(path, "/deny", "$user:(R)"), "icacls could not deny reading $path")
    try {
        return block()
    } finally {
        icacls(path, "/remove:d", user)
    }
}

private fun icacls(path: Path, vararg arguments: String): Boolean = runCatching {
    val process = ProcessBuilder("icacls", path.toString(), *arguments).redirectErrorStream(true).start()
    process.inputStream.readAllBytes()
    process.waitFor() == 0
}.getOrDefault(false)
