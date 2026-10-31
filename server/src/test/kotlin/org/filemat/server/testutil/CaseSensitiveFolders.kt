package org.filemat.server.testutil

import java.nio.file.Files
import java.nio.file.Path

/**
 * Creates a Windows folder with the per-directory case-sensitive flag (`fsutil file setCaseSensitiveInfo`).
 * Skips the test when the flag can't be set, for example on FAT or without the Windows feature (see [assumeWindowsFeature]).
 */
fun createCaseSensitiveFolder(parent: Path, name: String): Path {
    val folder = Files.createDirectories(parent.resolve(name))
    setCaseSensitiveFlag(folder, enable = true)
    return folder
}

/**
 * Turns the case-sensitive flag of [folder] off. New folders inherit the flag of their parent, so a test that needs a normal
 * folder inside a case-sensitive one calls this. Skips the test when the flag can't be changed.
 */
fun clearCaseSensitiveFlag(folder: Path) = setCaseSensitiveFlag(folder, enable = false)

private fun setCaseSensitiveFlag(folder: Path, enable: Boolean) {
    val state = if (enable) "enable" else "disable"
    val process = ProcessBuilder("fsutil", "file", "setCaseSensitiveInfo", folder.toString(), state)
        .redirectErrorStream(true)
        .start()
    process.inputStream.readAllBytes()
    assumeWindowsFeature(process.waitFor() == 0, "fsutil could not $state the case-sensitive flag of $folder")
}
