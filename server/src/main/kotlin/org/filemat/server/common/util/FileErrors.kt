package org.filemat.server.common.util

import org.filemat.server.common.model.Result
import org.filemat.server.config.platform.Platform
import org.filemat.server.config.platform.WindowsNative
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributes

/**
 * Why a file operation failed, when the reason is worth telling the user
 */
enum class FileFailure(val message: String, val detail: String) {
    IN_USE("File is in use.", "Some files are in use by another program."),
    READ_ONLY("File is read-only.", "Read-only files were left in place."),
    FOLDER_LINK("Links to folders cannot be copied.", "Links to folders were not copied."),
}

/**
 * Windows file errors (§6.1, §6.2): files held open by other programs and the read-only attribute
 */
object FileErrors {
    private const val retries = 3
    private const val retryDelayMs = 100L
    private const val retryBudgetMs = 3_000L
    private val retryBudget = ThreadLocal<RetryBudget?>()

    // ERROR_SHARING_VIOLATION, ERROR_LOCK_VIOLATION
    private val inUseReasons: Set<String> by lazy {
        listOf(32, 33).mapNotNull { WindowsNative.errorMessage(it)?.let(::normalizeReason) }.toSet()
    }

    /**
     * Returns whether [e] means another program has the file open or locked.
     * The JDK maps these errors to a plain `FileSystemException` (opening, moving) or `IOException` (reading, writing)
     * whose text is the system message, which is localized.
     */
    fun isInUse(e: Throwable): Boolean {
        if (!Platform.isWindows) return false
        // Subclasses such as AccessDeniedException come from other error codes
        val reason = when (e.javaClass) {
            FileSystemException::class.java -> (e as FileSystemException).reason
            IOException::class.java -> e.message
            else -> null
        } ?: return false
        return normalizeReason(reason) in inUseReasons
    }

    /**
     * Returns whether [e] from replacing [target] means another program has it open.
     * Windows reports that as access denied, so a target that isn't read-only and that the account may write to counts as in use.
     * Only for the step that replaces the target: access denied while making the replacement file is a missing right.
     */
    fun isTargetInUse(e: Throwable, target: Path): Boolean {
        if (isInUse(e)) return true
        if (!Platform.isWindows || e !is AccessDeniedException) return false
        return !isReadOnlyFile(target) && Files.isWritable(target)
    }

    /**
     * The error Windows gives for a file that another program has locked part of, so [isInUse] counts it.
     * For a lock found by asking (`FileChannel.tryLock`), where no call failed.
     */
    fun lockViolation(path: Path): IOException {
        val windowsMessage = if (Platform.isWindows) WindowsNative.errorMessage(33)?.trim() else null
        return IOException(windowsMessage ?: "Another program has locked part of $path.")
    }

    /**
     * Returns whether [e] was caused by the read-only attribute of [path]
     */
    fun isReadOnly(e: Throwable, path: Path): Boolean {
        if (e !is AccessDeniedException) return false
        return isReadOnlyFile(path)
    }

    /**
     * Returns whether [path] is a file with the Windows read-only attribute. Folders are never read-only (§6.2).
     */
    fun isReadOnlyFile(path: Path): Boolean {
        if (!Platform.isWindows) return false
        return runCatching {
            val attributes = Files.readAttributes(path, DosFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            attributes.isReadOnly && !attributes.isDirectory
        }.getOrDefault(false)
    }

    /**
     * Returns the reason [e] failed on [path], or null for other errors
     */
    fun failureOf(e: Throwable, path: Path): FileFailure? = when {
        isInUse(e) -> FileFailure.IN_USE
        isReadOnly(e, path) -> FileFailure.READ_ONLY
        else -> null
    }

    /**
     * Runs [action] and retries it 3 × 100 ms while the file is in use (Q39). Antivirus and indexer locks are usually brief.
     * Inside [withRetryBudget] the waits of all files together stop at the budget, then a file in use fails at once.
     */
    fun <T> retryWhenInUse(shouldRetry: (IOException) -> Boolean = ::isInUse, action: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return action()
            } catch (e: IOException) {
                if (attempt >= retries || !shouldRetry(e) || !takeRetryTime()) throw e
                attempt++
                Thread.sleep(retryDelayMs)
            }
        }
    }

    /**
     * Runs [action], a whole file operation that holds a lock, so that its retries wait [totalMs] at most.
     * Without it a folder of locked files would hold the lock for 300 ms per file. A nested call keeps the outer budget.
     */
    fun <T> withRetryBudget(totalMs: Long = retryBudgetMs, action: () -> T): T {
        if (retryBudget.get() != null) return action()
        retryBudget.set(RetryBudget(totalMs))
        try {
            return action()
        } finally {
            retryBudget.remove()
        }
    }

    /**
     * Takes one retry delay from the budget of the running operation. Without a budget, a retry is always allowed.
     */
    private fun takeRetryTime(): Boolean {
        val budget = retryBudget.get() ?: return true
        if (budget.remainingMs < retryDelayMs) return false
        budget.remainingMs -= retryDelayMs
        return true
    }

    private class RetryBudget(var remainingMs: Long)

    /**
     * Result for a single file that failed for [failure]. A file in use is a conflict (409), the rest are rejected.
     */
    fun <T> resultOf(failure: FileFailure): Result<T> = when (failure) {
        FileFailure.IN_USE -> Result.conflict(failure.message)
        FileFailure.READ_ONLY, FileFailure.FOLDER_LINK -> Result.reject(failure.message)
    }

    private fun normalizeReason(reason: String) = reason.trim().trimEnd('.')
}

/**
 * Reasons collected while a recursive file operation runs
 */
class FileFailures {
    private val reasons = linkedSetOf<FileFailure>()

    fun add(failure: FileFailure?) {
        if (failure != null) reasons += failure
    }

    /**
     * Result for failed items. [summary] says how many failed, and the reasons are appended to it.
     * When one file failed for a known reason, the message is that reason only.
     */
    fun <T> toResult(summary: String, isSingleFile: Boolean = false): Result<T> {
        if (isSingleFile && reasons.size == 1) return FileErrors.resultOf(reasons.first())

        val message = (listOf(summary) + reasons.map { it.detail }).joinToString(" ")
        return when {
            FileFailure.IN_USE in reasons -> Result.conflict(message)
            reasons.isNotEmpty() -> Result.reject(message)
            else -> Result.error(message)
        }
    }
}
