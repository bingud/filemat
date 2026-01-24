package org.filemat.server.module.file.service

import org.springframework.stereotype.Service
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock

enum class LockType {
    READ,
    WRITE
}

class LockResult<T>(private val value: T?, private val acquired: Boolean) {
    fun onFailure(block: () -> T): T {
        @Suppress("UNCHECKED_CAST")
        return if (acquired) value as T else block()
    }
}

data class FileLock(val successful: Boolean, val unlock: () -> Unit)

@Service
class FileLockService {
    private val lockRegistry = ConcurrentHashMap<Path, ReentrantReadWriteLock>()

    fun getLock(
        path: Path,
        type: LockType,
        checkChildren: Boolean = false
    ): FileLock {
        if (isAncestorLocked(path, type)) return FileLock(false) {}
        if (type == LockType.WRITE && checkChildren && isChildLocked(path)) return FileLock(false) {}

        val lock = lockRegistry.computeIfAbsent(path) { ReentrantReadWriteLock() }
        val selectedLock = if (type == LockType.WRITE) lock.writeLock() else lock.readLock()

        if (!selectedLock.tryLock()) return FileLock(false) {}

        if (isAncestorLocked(path, type) || (type == LockType.WRITE && checkChildren && isChildLocked(path))) {
            selectedLock.unlock()
            cleanup(path, lock)
            return FileLock(false) {}
        }

        return FileLock(true) {
            selectedLock.unlock()
            cleanup(path, lock)
        }
    }

    fun <T> tryWithLock(
        path: Path,
        type: LockType,
        checkChildren: Boolean = false,
        action: () -> T
    ): LockResult<T> {
        val lock = getLock(path, type, checkChildren)
        if (!lock.successful) return LockResult(null, false)

        return try {
            LockResult(action(), true)
        } finally {
            lock.unlock()
        }
    }

    fun <T> tryWithLock(
        paths: List<Path>,
        type: LockType,
        checkChildren: Boolean = false,
        action: () -> T
    ): LockResult<T> {
        val locks = paths.map { getLock(it, type, checkChildren) }
        if (locks.any { !it.successful }) {
            locks.forEach { it.unlock() }
            return LockResult(null, false)
        }

        return try {
            LockResult(action(), true)
        } finally {
            locks.forEach { it.unlock() }
        }
    }

    fun <T> tryWithLock(
        vararg pairs: Pair<Path?, LockType>,
        checkChildren: Boolean = false,
        action: () -> T
    ): LockResult<T> {
        val locks = pairs.mapNotNull { (path, type) ->
            if (path != null) getLock(path, type, checkChildren) else null
        }

        if (locks.any { !it.successful }) {
            locks.forEach { it.unlock() }
            return LockResult(null, false)
        }

        return try {
            LockResult(action(), true)
        } finally {
            locks.forEach { it.unlock() }
        }
    }

    private fun isAncestorLocked(path: Path, type: LockType): Boolean {
        var parent = path.parent
        while (parent != null) {
            val lock = lockRegistry[parent]
            if (lock != null) {
                if (lock.isWriteLocked && !lock.isWriteLockedByCurrentThread) return true
                if (type == LockType.WRITE && lock.readLockCount > 0) return true
            }
            parent = parent.parent
        }
        return false
    }

    private fun isChildLocked(path: Path): Boolean {
        return lockRegistry.entries.any { (lockPath, lock) ->
            lockPath.startsWith(path) && lockPath != path && (lock.readLockCount > 0 || lock.isWriteLocked)
        }
    }

    private fun cleanup(path: Path, lock: ReentrantReadWriteLock) {
        if (!lock.hasQueuedThreads() && lock.readLockCount == 0 && !lock.isWriteLocked) {
            lockRegistry.remove(path, lock)
        }
    }
}