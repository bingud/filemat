package org.filemat.server.module.sharedFile

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import org.filemat.server.common.model.Result
import org.filemat.server.common.State
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.sharedFile.model.FileShare
import org.filemat.server.testutil.assertNotFound
import org.filemat.server.testutil.assertSuccess
import org.filemat.server.testutil.directoryLinkOrSkip
import org.filemat.server.testutil.symlinkOrSkip
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test

class SharePathTest {

    @TempDir
    lateinit var tempDir: Path

    private val state = State(env = emptyMap())
    private lateinit var shareRoot: Path
    private lateinit var outside: Path

    @BeforeEach
    fun setup() {
        shareRoot = Files.createDirectory(tempDir.resolve("share"))
        outside = Files.createDirectory(tempDir.resolve("outside"))
        Files.writeString(shareRoot.resolve("inside.txt"), "inside")
        Files.writeString(outside.resolve("secret.txt"), "SECRET")
    }

    @AfterEach
    fun tearDown() {
        if (::shareRoot.isInitialized) deleteLinksIn(shareRoot)
        if (::outside.isInitialized) deleteLinksIn(outside)
    }

    @Test
    fun `share followSymlinks is hardcoded off`() {
        assertFalse(FileShare.followSymlinks)
    }

    @Test
    fun `resolves a regular file inside the share`() {
        state.app.followSymlinks = false

        val result = resolve("inside.txt")

        assertSuccess(result)
        assertEquals(shareRoot.resolve("inside.txt").toRealPath(), result.value.path)
    }

    @Test
    fun `resolves the share root`() {
        state.app.followSymlinks = false

        val result = resolve("/")

        assertSuccess(result)
        assertEquals(shareRoot.toRealPath(), result.value.path)
    }

    @Test
    fun `in-tree file symlink is followed when system follow is on`() {
        state.app.followSymlinks = true

        val nested = Files.createDirectory(shareRoot.resolve("nested"))
        val target = Files.writeString(nested.resolve("hello.txt"), "hello")
        symlinkOrSkip(shareRoot.resolve("alias"), nested)

        val result = resolve("alias/hello.txt")

        assertSuccess(result)
        assertEquals(target.toRealPath(), result.value.path)
    }

    @Test
    fun `in-tree file symlink is rejected when system follow is off`() {
        state.app.followSymlinks = false
        val target = shareRoot.resolve("inside.txt")
        symlinkOrSkip(shareRoot.resolve("alias-file"), target)

        val result = resolve("alias-file")

        assertNotFound(result)
    }

    @Test
    fun `outbound file symlink is not followed when share follow is off`() {
        state.app.followSymlinks = true

        val secret = outside.resolve("secret.txt")
        symlinkOrSkip(shareRoot.resolve("escape-file"), secret)

        val result = resolve("escape-file")

        assertNotFound(result)
        assertEquals("SECRET", Files.readString(secret))
    }

    @Test
    fun `outbound directory symlink is not followed when share follow is off`() {
        state.app.followSymlinks = true

        directoryLinkOrSkip(shareRoot.resolve("escape-dir"), outside)

        val result = resolve("escape-dir/secret.txt")

        assertNotFound(result)
        assertEquals("SECRET", Files.readString(outside.resolve("secret.txt")))
    }

    @Test
    fun `bounce-back symlink that leaves the share is not followed`() {
        state.app.followSymlinks = true

        val bounce = outside.resolve("bounce")
        symlinkOrSkip(bounce, shareRoot.resolve("inside.txt"))
        symlinkOrSkip(shareRoot.resolve("leave-and-return"), bounce)

        val result = resolve("leave-and-return")

        assertNotFound(result)
    }

    @Test
    fun `outbound symlink is followed when share follow is enabled`() {
        state.app.followSymlinks = true

        val secret = outside.resolve("secret.txt")
        symlinkOrSkip(shareRoot.resolve("escape-file"), secret)

        val result = resolve("escape-file", followOutbound = true)

        assertSuccess(result)
        assertEquals(secret.toRealPath(), result.value.path)
    }

    @Test
    fun `descendant of a nested file is accepted`() {
        state.app.followSymlinks = false
        val nested = Files.createDirectory(shareRoot.resolve("nested"))
        val hello = Files.writeString(nested.resolve("hello.txt"), "hello")

        val result = resolveSharedDescendant(hello, FilePath.ofAlreadyNormalized(shareRoot), state.app.followSymlinks)

        assertSuccess(result)
        assertEquals(hello.toRealPath(), result.value.path)
    }

    @Test
    fun `descendant outbound symlink is rejected`() {
        state.app.followSymlinks = true
        val secret = outside.resolve("secret.txt")
        val escape = shareRoot.resolve("escape-file")
        symlinkOrSkip(escape, secret)

        val result = resolveSharedDescendant(escape, FilePath.ofAlreadyNormalized(shareRoot), state.app.followSymlinks)

        assertNotFound(result)
    }

    @Test
    fun `descendant bounce-back symlink is rejected`() {
        state.app.followSymlinks = true

        val bounce = outside.resolve("bounce")
        symlinkOrSkip(bounce, shareRoot.resolve("inside.txt"))
        val leave = shareRoot.resolve("leave-and-return")
        symlinkOrSkip(leave, bounce)

        val result = resolveSharedDescendant(leave, FilePath.ofAlreadyNormalized(shareRoot), state.app.followSymlinks)

        assertNotFound(result)
    }

    @Test
    fun `descendant outside the share is rejected`() {
        state.app.followSymlinks = false

        val result = resolveSharedDescendant(outside.resolve("secret.txt"), FilePath.ofAlreadyNormalized(shareRoot), state.app.followSymlinks)

        assertNotFound(result)
    }

    private fun resolve(relative: String, followOutbound: Boolean = FileShare.followSymlinks): Result<FilePath> {
        return resolveSharedFilePath(
            relativePath = FilePath.of(relative),
            shareRoot = FilePath.ofAlreadyNormalized(shareRoot),
            followSymlinks = state.app.followSymlinks,
            followOutboundSymlinks = followOutbound,
        )
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
