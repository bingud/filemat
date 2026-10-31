package org.filemat.server.module.file.service

import org.filemat.server.testutil.createCaseSensitiveFolder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.concurrent.thread

class FileLockServiceTest {

    private val service = FileLockService()

    @TempDir
    lateinit var dir: Path

    /**
     * Tries a lock on another thread, because the locks are reentrant for the thread that holds them
     */
    private fun canLockElsewhere(path: Path, type: LockType = LockType.WRITE, checkChildren: Boolean = false): Boolean {
        var successful = false
        thread {
            val lock = service.getLock(path, type, checkChildren)
            successful = lock.successful
            lock.unlock()
        }.join()
        return successful
    }

    @Test
    fun `a write lock blocks the same path and its children`() {
        val lock = service.getLock(dir.resolve("a"), LockType.WRITE)

        assertFalse(canLockElsewhere(dir.resolve("a")))
        assertFalse(canLockElsewhere(dir.resolve("a").resolve("b"), LockType.READ))
        assertTrue(canLockElsewhere(dir.resolve("ab")))

        lock.unlock()
        assertTrue(canLockElsewhere(dir.resolve("a")))
    }

    @Test
    fun `read locks are shared`() {
        val lock = service.getLock(dir.resolve("a"), LockType.READ)

        assertTrue(canLockElsewhere(dir.resolve("a"), LockType.READ))
        assertFalse(canLockElsewhere(dir.resolve("a")))

        lock.unlock()
    }

    @Test
    fun `checkChildren finds locked children by whole segments`() {
        val lock = service.getLock(dir.resolve("a").resolve("b"), LockType.READ)

        assertFalse(canLockElsewhere(dir.resolve("a"), checkChildren = true))
        assertTrue(canLockElsewhere(dir.resolve("a"), checkChildren = false))
        assertTrue(canLockElsewhere(dir.resolve("a").resolve("bc"), checkChildren = true))

        lock.unlock()
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `names that differ in case share a lock in a normal Windows folder`() {
        val lock = service.getLock(dir.resolve("file.txt"), LockType.WRITE)

        assertFalse(canLockElsewhere(dir.resolve("FILE.TXT")))

        lock.unlock()
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `names that differ in case have separate locks in a case-sensitive folder`() {
        val folder = createCaseSensitiveFolder(dir, "proj")
        val lock = service.getLock(folder.resolve("file.txt"), LockType.WRITE)

        assertTrue(canLockElsewhere(folder.resolve("FILE.TXT")))
        assertFalse(canLockElsewhere(dir.resolve("PROJ").resolve("file.txt")))

        lock.unlock()
    }
}
