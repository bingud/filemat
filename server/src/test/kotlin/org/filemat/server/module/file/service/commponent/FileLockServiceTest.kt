package org.filemat.server.module.file.service.commponent

import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.LockType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class FileLockServiceTest {

    private val lockService = FileLockService()

    @Test
    fun `should fail write lock if path is already read locked`() {
        val path = Paths.get("/test/file.txt")

        // 1. Acquire Read Lock
        val readLock = lockService.getLock(path, LockType.READ)
        assertTrue(readLock.successful)

        // 2. Attempt Write Lock on same path (should fail)
        val writeLock = lockService.getLock(path, LockType.WRITE)
        assertFalse(writeLock.successful)

        // 3. Cleanup
        readLock.unlock()

        // 4. Attempt Write Lock again (should now succeed)
        val writeLockRetry = lockService.getLock(path, LockType.WRITE)
        assertTrue(writeLockRetry.successful)
        writeLockRetry.unlock()
    }

    @Test
    fun `should fail child write lock if parent is read locked`() {
        val parent = Paths.get("/data")
        val child = Paths.get("/data/item")

        // 1. Lock Parent
        val parentLock = lockService.getLock(parent, LockType.READ)
        assertTrue(parentLock.successful)

        // 2. Lock Child (should fail due to isAncestorLocked check)
        val childLock = lockService.getLock(child, LockType.WRITE)
        assertFalse(childLock.successful)

        parentLock.unlock()
    }

    @Test
    fun `should fail parent write lock if child is read locked`() {
        val parent = Paths.get("/data")
        val child = Paths.get("/data/item")

        // 1. Lock child
        val childLock = lockService.getLock(child, LockType.READ)
        assertTrue(childLock.successful)

        // 2. Lock parent (should fail)
        val parentLock = lockService.getLock(parent, LockType.WRITE, checkChildren = true)
        assertFalse(parentLock.successful)

        parentLock.unlock()
    }
}