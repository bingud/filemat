package org.filemat.server.testutil

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path

/**
 * Creates a symbolic link, or skips the test if the OS/user cannot create symlinks.
 * Set `-Dfilemat.requireSymlinks=true` (or env FILEMAT_REQUIRE_SYMLINKS=true) in CI to fail instead of skip.
 */
fun symlinkOrSkip(link: Path, target: Path) {
    val error = runCatching { Files.createSymbolicLink(link, target) }.exceptionOrNull() ?: return
    skipOrFail("Could not create symbolic link $link -> $target: ${error.message}")
}

/** Like [symlinkOrSkip], but falls back to an NTFS junction on Windows, which needs no privileges. */
fun directoryLinkOrSkip(link: Path, target: Path) {
    if (runCatching { Files.createSymbolicLink(link, target) }.isSuccess) return

    val output = runCatching {
        val process = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toAbsolutePath().toString())
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().readText()
        if (process.waitFor() == 0 && Files.exists(link)) return
        text
    }.getOrElse { it.message ?: "" }
    skipOrFail("Could not create directory link $link -> $target: $output")
}

private fun skipOrFail(message: String) {
    val required = System.getProperty("filemat.requireSymlinks") == "true" ||
        System.getenv("FILEMAT_REQUIRE_SYMLINKS") == "true"
    if (required) throw AssertionError(message)
    assumeTrue(false, message)
}
