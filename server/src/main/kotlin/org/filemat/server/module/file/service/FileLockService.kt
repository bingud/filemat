package org.filemat.server.module.file.service

import org.filemat.server.config.platform.PathPolicy
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

/**
 * In-process file locks, keyed by path key (`PathPolicy.pathKey`).
 * `Path` keys would ignore case on Windows, so `a.txt` and `A.txt` in a case-sensitive folder would share a lock.
 */
@Service
class FileLockService {
    private val lockRegistry = ConcurrentHashMap<String, ReentrantReadWriteLock>()

    fun getLock(
        path: Path,
        type: LockType,
        checkChildren: Boolean = false
    ): FileLock {
        val key = PathPolicy.pathKey(path)
        if (isAncestorLocked(key, type)) return FileLock(false) {}
        if (type == LockType.WRITE && checkChildren && isChildLocked(key)) return FileLock(false) {}

        val lock = lockRegistry.computeIfAbsent(key) { ReentrantReadWriteLock() }
        val selectedLock = if (type == LockType.WRITE) lock.writeLock() else lock.readLock()

        if (!selectedLock.tryLock()) return FileLock(false) {}

        if (isAncestorLocked(key, type) || (type == LockType.WRITE && checkChildren && isChildLocked(key))) {
            selectedLock.unlock()
            cleanup(key, lock)
            return FileLock(false) {}
        }

        return FileLock(true) {
            selectedLock.unlock()
            cleanup(key, lock)
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

    private fun isAncestorLocked(key: String, type: LockType): Boolean {
        val segments = PathPolicy.segments(key)
        for (depth in segments.lastIndex downTo 0) {
            val lock = lockRegistry[PathPolicy.join(segments.subList(0, depth))] ?: continue
            if (lock.isWriteLocked && !lock.isWriteLockedByCurrentThread) return true
            if (type == LockType.WRITE && lock.readLockCount > 0) return true
        }
        return false
    }

    private fun isChildLocked(key: String): Boolean {
        return lockRegistry.entries.any { (lockKey, lock) ->
            lockKey != key && PathPolicy.isKeyWithin(lockKey, key) && (lock.readLockCount > 0 || lock.isWriteLocked)
        }
    }

    private fun cleanup(key: String, lock: ReentrantReadWriteLock) {
        if (!lock.hasQueuedThreads() && lock.readLockCount == 0 && !lock.isWriteLocked) {
            lockRegistry.remove(key, lock)
        }
    }
}