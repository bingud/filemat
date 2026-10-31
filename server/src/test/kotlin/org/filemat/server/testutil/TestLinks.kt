package org.filemat.server.testutil

import org.filemat.server.config.platform.Platform
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
    val error = createJunction(link, target) ?: return
    skipOrFail("Could not create directory link $link -> $target: $error")
}

/**
 * Creates an NTFS junction, or skips the test on other platforms or when `mklink /J` fails.
 * Fails instead with either switch, FILEMAT_REQUIRE_SYMLINKS or FILEMAT_REQUIRE_WINDOWS_FEATURES.
 */
fun junctionOrSkip(link: Path, target: Path) {
    assumeTrue(Platform.isWindows, "Junctions only exist on Windows")
    val error = createJunction(link, target) ?: return
    skipOrFail("Could not create junction $link -> $target: $error", isRequired = requiresWindowsFeatures())
}

/** Runs `mklink /J`. Returns null when the junction exists afterwards, or else what went wrong. */
private fun createJunction(link: Path, target: Path): String? = runCatching {
    val process = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toAbsolutePath().toString())
        .redirectErrorStream(true)
        .start()
    val text = process.inputStream.bufferedReader().readText()
    if (process.waitFor() == 0 && Files.exists(link)) null else text
}.getOrElse { it.message ?: "" }

private fun skipOrFail(message: String, isRequired: Boolean = false) {
    val required = isRequired || System.getProperty("filemat.requireSymlinks") == "true" ||
        System.getenv("FILEMAT_REQUIRE_SYMLINKS") == "true"
    if (required) throw AssertionError(message)
    assumeTrue(false, message)
}
