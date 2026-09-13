package org.filemat.server.module.sharedFile

import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.sharedFile.model.FileShare
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class ResolveSharedFilePathTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var shareRoot: Path
    private lateinit var outside: Path
    private var previousFollowSymlinks = false

    @BeforeEach
    fun setup() {
        previousFollowSymlinks = State.App.followSymlinks
        shareRoot = Files.createDirectory(tempDir.resolve("share"))
        outside = Files.createDirectory(tempDir.resolve("outside"))
        Files.writeString(shareRoot.resolve("inside.txt"), "inside")
        Files.writeString(outside.resolve("secret.txt"), "SECRET")
    }

    @AfterEach
    fun tearDown() {
        State.App.followSymlinks = previousFollowSymlinks
        if (::shareRoot.isInitialized) deleteLinksIn(shareRoot)
        if (::outside.isInitialized) deleteLinksIn(outside)
    }

    @Test
    fun `share followSymlinks is hardcoded off`() {
        assertFalse(FileShare.followSymlinks)
    }

    @Test
    fun `resolves a regular file inside the share`() {
        State.App.followSymlinks = false

        val result = resolve("inside.txt")

        assertTrue(result.isSuccessful)
        assertEquals(shareRoot.resolve("inside.txt").toRealPath(), result.value.path)
    }

    @Test
    fun `resolves the share root`() {
        State.App.followSymlinks = false

        val result = resolve("/")

        assertTrue(result.isSuccessful)
        assertEquals(shareRoot.toRealPath(), result.value.path)
    }

    @Test
    fun `in-tree file symlink is followed when system follow is on`() {
        State.App.followSymlinks = true
        assumeFollowSymlinksHonored()

        val nested = Files.createDirectory(shareRoot.resolve("nested"))
        val target = Files.writeString(nested.resolve("hello.txt"), "hello")
        createSymbolicLinkOrSkip(shareRoot.resolve("alias"), nested)

        val result = resolve("alias/hello.txt")

        assertTrue(result.isSuccessful)
        assertEquals(target.toRealPath(), result.value.path)
    }

    @Test
    fun `in-tree file symlink is rejected when system follow is off`() {
        State.App.followSymlinks = false
        val target = shareRoot.resolve("inside.txt")
        createSymbolicLinkOrSkip(shareRoot.resolve("alias-file"), target)

        val result = resolve("alias-file")

        assertTrue(result.notFound)
    }

    @Test
    fun `outbound file symlink is not followed when share follow is off`() {
        State.App.followSymlinks = true
        assumeFollowSymlinksHonored()

        val secret = outside.resolve("secret.txt")
        createSymbolicLinkOrSkip(shareRoot.resolve("escape-file"), secret)

        val result = resolve("escape-file")

        assertTrue(result.notFound)
        assertEquals("SECRET", Files.readString(secret))
    }

    @Test
    fun `outbound directory symlink is not followed when share follow is off`() {
        State.App.followSymlinks = true
        assumeFollowSymlinksHonored()

        createDirectoryLinkOrSkip(shareRoot.resolve("escape-dir"), outside)

        val result = resolve("escape-dir/secret.txt")

        assertTrue(result.notFound)
        assertEquals("SECRET", Files.readString(outside.resolve("secret.txt")))
    }

    @Test
    fun `bounce-back symlink that leaves the share is not followed`() {
        State.App.followSymlinks = true
        assumeFollowSymlinksHonored()

        val bounce = outside.resolve("bounce")
        createSymbolicLinkOrSkip(bounce, shareRoot.resolve("inside.txt"))
        createSymbolicLinkOrSkip(shareRoot.resolve("leave-and-return"), bounce)

        val result = resolve("leave-and-return")

        assertTrue(result.notFound)
    }

    @Test
    fun `outbound symlink is followed when share follow is enabled`() {
        State.App.followSymlinks = true
        assumeFollowSymlinksHonored()

        val secret = outside.resolve("secret.txt")
        createSymbolicLinkOrSkip(shareRoot.resolve("escape-file"), secret)

        val result = resolve("escape-file", followOutbound = true)

        assertTrue(result.isSuccessful)
        assertEquals(secret.toRealPath(), result.value.path)
    }

    @Test
    fun `descendant of a nested file is accepted`() {
        State.App.followSymlinks = false
        val nested = Files.createDirectory(shareRoot.resolve("nested"))
        val hello = Files.writeString(nested.resolve("hello.txt"), "hello")

        val result = resolveSharedDescendant(hello, FilePath.ofAlreadyNormalized(shareRoot))

        assertTrue(result.isSuccessful)
        assertEquals(hello.toRealPath(), result.value.path)
    }

    @Test
    fun `descendant outbound symlink is rejected`() {
        State.App.followSymlinks = true
        assumeFollowSymlinksHonored()
        val secret = outside.resolve("secret.txt")
        val escape = shareRoot.resolve("escape-file")
        createSymbolicLinkOrSkip(escape, secret)

        val result = resolveSharedDescendant(escape, FilePath.ofAlreadyNormalized(shareRoot))

        assertTrue(result.notFound)
    }

    @Test
    fun `descendant bounce-back symlink is rejected`() {
        State.App.followSymlinks = true
        assumeFollowSymlinksHonored()

        val bounce = outside.resolve("bounce")
        createSymbolicLinkOrSkip(bounce, shareRoot.resolve("inside.txt"))
        val leave = shareRoot.resolve("leave-and-return")
        createSymbolicLinkOrSkip(leave, bounce)

        val result = resolveSharedDescendant(leave, FilePath.ofAlreadyNormalized(shareRoot))

        assertTrue(result.notFound)
    }

    @Test
    fun `descendant outside the share is rejected`() {
        State.App.followSymlinks = false

        val result = resolveSharedDescendant(outside.resolve("secret.txt"), FilePath.ofAlreadyNormalized(shareRoot))

        assertTrue(result.notFound)
    }

    private fun resolve(relative: String, followOutbound: Boolean = FileShare.followSymlinks): Result<FilePath> {
        return resolveSharedFilePath(
            relativePath = FilePath.of(relative),
            shareRoot = FilePath.ofAlreadyNormalized(shareRoot),
            followOutboundSymlinks = followOutbound,
        )
    }

    private fun assumeFollowSymlinksHonored() {
        assumeTrue(State.App.followSymlinks, "followSymlinks is locked by environment variable")
    }

    private fun createSymbolicLinkOrSkip(link: Path, target: Path) {
        assumeTrue(
            runCatching { Files.createSymbolicLink(link, target) }.isSuccess,
            "Could not create symbolic link",
        )
    }

    private fun createDirectoryLinkOrSkip(link: Path, target: Path) {
        try {
            Files.createSymbolicLink(link, target)
            return
        } catch (_: Exception) {
        }

        val process = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toAbsolutePath().toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assumeTrue(process.waitFor() == 0 && Files.exists(link), "Could not create directory link: $output")
    }

    private fun deleteLinksIn(dir: Path) {
        if (!Files.isDirectory(dir)) return
        runCatching {
            Files.list(dir).use { stream ->
                stream.forEach { child ->
                    val isLink = Files.isSymbolicLink(child)
                    val isJunction = !isLink && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) &&
                        runCatching { child.toRealPath() != child.toAbsolutePath().normalize() }.getOrDefault(false)
                    if (isLink || isJunction) {
                        Files.deleteIfExists(child)
                    }
                }
            }
        }
    }
}
