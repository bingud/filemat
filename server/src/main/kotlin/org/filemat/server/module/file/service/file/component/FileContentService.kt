package org.filemat.server.module.file.service.file.component

import org.apache.commons.io.input.BoundedInputStream
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.FileErrors
import org.filemat.server.common.util.StringUtils
import org.filemat.server.common.util.isAlreadyWalked
import org.filemat.server.common.util.plusWalkIdentity
import org.filemat.server.common.util.reachesNetwork
import org.filemat.server.common.util.resolvePath
import org.filemat.server.common.util.safeStreamSkip
import org.filemat.server.config.platform.LinkInspector
import org.filemat.server.config.platform.PathPolicy
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FullFileMetadata
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.LockType
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.sharedFile.resolveSharedDescendant
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.invariantSeparatorsPathString

@Service
class FileContentService(
    private val fileService: FileService,
    private val fileLockService: FileLockService,
    private val filesystemService: FilesystemService,
    private val logService: LogService,
    private val state: State,
) {

    /**
     * Returns an input stream for the content of a file
     *
     * Optionally returns a byte range
     */
    fun getFileContent(
        user: Principal?,
        rawPath: FilePath,
        existingCanonicalPath: FilePath? = null,
        range: LongRange? = null,
        ignorePermissions: Boolean = false
    ): Result<InputStream> {
        val canonicalPath = existingCanonicalPath ?: resolvePath(rawPath, state.app.followSymlinks).let { path ->
            if (path.isNotSuccessful) {
                return path.cast()
            }
            path.value
        }

        fileService.isAllowedToAccessFile(user, canonicalPath, ignorePermissions = ignorePermissions).let {
            if (it.isNotSuccessful) return it.cast()
        }

        // OneDrive placeholders are downloaded by reading them (Q42)
        if (!LinkInspector.isRegularFile(canonicalPath.path)) return Result.notFound()

        val lock = fileLockService.getLock(canonicalPath.path, LockType.READ)
        if (!lock.successful) return Result.reject("This file is currently being modified.")

        try {
            val fileInputStream = Files.newInputStream(canonicalPath.path)
            if (range == null) return fileInputStream.toResult()

            safeStreamSkip(fileInputStream, range.first)
                .let { if (!it) return Result.error("Failed to get the requested range from a stream.") }

            val bounded = BoundedInputStream.builder()
                .setInputStream(fileInputStream)
                .setMaxCount((range.last - range.first) + 1)
                .get()

            return bounded.toResult()
        } catch (e: Exception) {
            return Result.error("Failed to stream file.")
        } finally {
            lock.unlock()
        }
    }

    fun addFileToZip(
        zip: ZipOutputStream,
        rawPath: FilePath,
        existingBaseZipPath: Path?,
        principal: Principal?,
        shareToken: String?
    ) {
        val isShared = shareToken != null
        val shareRoot: FilePath? = if (isShared) {
            fileService.resolvePathWithOptionalShare(FilePath.of(PathPolicy.root), shareToken).let {
                if (it.isNotSuccessful) return
                it.value
            }
        } else null

        // 1. Resolve Initial Path
        val canonicalPathResult = fileService.resolvePathWithOptionalShare(
            path = rawPath,
            shareToken = shareToken
        )

        val canonicalPath = canonicalPathResult.let {
            if (it.isNotSuccessful) return
            it.value
        }
        // The Windows drive list cannot be zipped as a whole
        if (PathPolicy.isVirtualRoot(canonicalPath.path)) return

        // 2. Setup Protection and Config
        val isReadDataFolderProtected = state.app.allowReadDataFolder == false
        val copyResolvedSymlinks = state.app.followSymlinks

        // Cannot zip FROM protected data folder (Read protection)
        if (isReadDataFolderProtected) {
            if (state.app.isInDataFolder(canonicalPath.path)) {
                return
            }
        }

        val baseZipPath: Path? = existingBaseZipPath ?: canonicalPath.path.fileName

        // 3. Begin Recursive Traversal
        zipRecursiveSafe(
            currentSource = canonicalPath.path,
            currentZipPath = baseZipPath, // Can be null if zipping root, usually handled by caller
            zip = zip,
            user = principal,
            protectedPath = if (isReadDataFolderProtected) state.app.dataFolderPath else null,
            ignorePermissions = isShared,
            copyResolvedSymlinks = copyResolvedSymlinks,
            shareRoot = shareRoot,
            ancestorRealPaths = emptySet(),
        )
    }

    private fun zipEntryName(path: Path?): String? {
        if (path == null) return null
        return path.invariantSeparatorsPathString
    }

    private fun logZipSkip(path: Path, reason: String, e: Exception? = null) {
        logService.warn(
            type = LogType.SYSTEM,
            action = UserAction.READ_FILE,
            description = "Skipped file while building zip download.",
            message = buildString {
                append("path=")
                append(path.invariantSeparatorsPathString)
                append("; reason=")
                append(reason)
                if (e != null) {
                    append("; error=")
                    append(e.message ?: e::class.java.simpleName)
                }
            },
        )
    }

    private fun zipRecursiveSafe(
        currentSource: Path,
        currentZipPath: Path?,
        zip: ZipOutputStream,
        user: Principal?,
        protectedPath: Path?,
        ignorePermissions: Boolean,
        copyResolvedSymlinks: Boolean,
        shareRoot: FilePath?,
        ancestorRealPaths: Set<Path>,
    ): Int {
        var failedCount = 0
        val sourceFilePath = FilePath.ofAlreadyNormalized(currentSource)

        // Explicit protection check
        if (protectedPath != null && currentSource == protectedPath) {
            logZipSkip(currentSource, "protected data folder")
            return 1
        }

        if (shareRoot != null && resolveSharedDescendant(currentSource, shareRoot, state.app.followSymlinks).isNotSuccessful) {
            return failedCount
        }

        val isSymlink = Files.isSymbolicLink(currentSource)
        if (isSymlink && !copyResolvedSymlinks) return 0
        // A link to a network folder is never followed (§4.3)
        if (isSymlink && reachesNetwork(currentSource)) {
            logZipSkip(currentSource, "link to a network folder")
            return failedCount + 1
        }

        val realPath = try {
            if (copyResolvedSymlinks) currentSource.toRealPath()
            else currentSource.toRealPath(LinkOption.NOFOLLOW_LINKS)
        } catch (e: Exception) {
            logZipSkip(currentSource, "failed to resolve real path", e)
            return failedCount + 1
        }

        // 1. Determine Type (Dir vs Symlink)
        val isDirectory = if (copyResolvedSymlinks) {
            Files.isDirectory(currentSource)
        } else {
            Files.isDirectory(currentSource, LinkOption.NOFOLLOW_LINKS)
        }

        val resolvedPath = if (isSymlink) {
            resolvePath(sourceFilePath, state.app.followSymlinks).let { result ->
                if (result.isNotSuccessful) {
                    logZipSkip(currentSource, "failed to resolve symlink")
                    return failedCount + 1
                }
                result.value
            }
        } else null

        val childAncestors = if (isDirectory) {
            if (currentSource.isAlreadyWalked(ancestorRealPaths)) return failedCount
            ancestorRealPaths.plusWalkIdentity(currentSource)
        } else ancestorRealPaths

        val pathForAccess = resolvedPath ?: FilePath.ofAlreadyNormalized(realPath)

        return fileLockService.tryWithLock(
            currentSource to LockType.READ,
            resolvedPath?.path to LockType.READ,
        ) {
            fileService.isAllowedToAccessFile(
                user = user,
                canonicalPath = pathForAccess,
                ignorePermissions = ignorePermissions
            ).let {
                if (it.isNotSuccessful) {
                    logZipSkip(currentSource, "permission denied")
                    return@tryWithLock failedCount + 1
                }
            }

            // 3. Handle Directory Recursion
            if (isDirectory) {
                try {
                    // Add directory entry to Zip (must end in /)
                    val dirName = zipEntryName(currentZipPath)
                    if (dirName != null) {
                        val dirEntryName = if (dirName.endsWith("/")) dirName else "$dirName/"
                        zip.putNextEntry(ZipEntry(dirEntryName))
                        zip.closeEntry()
                    }

                    Files.newDirectoryStream(currentSource).use { stream ->
                        for (child in stream) {
                            failedCount += zipRecursiveSafe(
                                currentSource = child,
                                currentZipPath = currentZipPath?.resolve(child.fileName) ?: child.fileName,
                                zip = zip,
                                user = user,
                                protectedPath = protectedPath,
                                ignorePermissions = ignorePermissions,
                                copyResolvedSymlinks = copyResolvedSymlinks,
                                shareRoot = shareRoot,
                                ancestorRealPaths = childAncestors,
                            )
                        }
                    }
                } catch (e: Exception) {
                    logZipSkip(currentSource, "failed to list directory", e)
                    return@tryWithLock failedCount + 1
                }
                return@tryWithLock failedCount
            }

            // 4. Write File to Zip
            try {
                val entryName = zipEntryName(currentZipPath) ?: currentSource.fileName.invariantSeparatorsPathString
                zip.putNextEntry(ZipEntry(entryName))
                try {
                    val inputOptions = if (copyResolvedSymlinks) emptyArray() else arrayOf(LinkOption.NOFOLLOW_LINKS)

                    // Use standard InputStream. getFileContent is not needed as we did manual perm checks above
                    Files.newInputStream(currentSource, *inputOptions).use { inputStream ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        val buffered = BufferedInputStream(inputStream)
                        while (buffered.read(buffer).also { bytesRead = it } != -1) {
                            zip.write(buffer, 0, bytesRead)
                        }
                    }
                } finally {
                    try {
                        zip.closeEntry()
                    } catch (_: Exception) {
                    }
                }
                zip.flush()
            } catch (e: Exception) {
                logZipSkip(currentSource, "failed to write entry", e)
                return@tryWithLock failedCount + 1
            }

            return@tryWithLock failedCount
        }.onFailure {
            logZipSkip(currentSource, "could not acquire read lock")
            failedCount + 1
        }
    }

    data class EditFileResult(val modifiedDate: Long, val size: Long)
    fun editFile(user: Principal, rawPath: FilePath, newContent: String): Result<EditFileResult> {
        val canonicalResult = resolvePath(rawPath, state.app.followSymlinks)
        val canonicalPath = canonicalResult.let {
            if (it.isNotSuccessful) return canonicalResult.cast()
            it.value
        }

        fileService.isAllowedToEditFile(
            user = user,
            canonicalPath = canonicalPath
        ).let {
            if (it.isNotSuccessful) return it.cast()
        }

        val file = canonicalPath.path.toFile()
        if (!file.exists()) {
            return Result.notFound()
        }

        val lock = fileLockService.getLock(canonicalPath.path, LockType.WRITE)
        if (!lock.successful) return Result.reject("This file is currently being modified.")
        try {
            try {
                FileErrors.retryWhenInUse { writeInPlace(canonicalPath.path, newContent) }
            } catch (e: Exception) {
                // Read-only files stay read-only (Q38)
                FileErrors.failureOf(e, canonicalPath.path)?.let { return FileErrors.resultOf(it) }
                return Result.error("An error occurred while saving file.")
            }

            val modifiedDate = runCatching { file.lastModified() }.getOrElse { System.currentTimeMillis() }
            val size = filesystemService.getSize(canonicalPath).let {
                if (it.isNotSuccessful) return@let StringUtils.measureByteSize(newContent)
                it.value
            }

            return Result.ok(
                EditFileResult(modifiedDate = modifiedDate, size = size)
            )
        } finally {
            lock.unlock()
        }
    }

    /**
     * Writes over a file, then cuts it to size. NIO opens it with share-delete, unlike java.io (§6.1).
     * The file stays the same file (file ID, ACL, hard links), which a temp file swapped in would not.
     * Truncating first would empty a file that another program has locked, before the write fails.
     *
     * A failure leaves the old content, so the save can't end half old and half new:
     * - the whole file is locked first, so another program's lock on part of it stops the save before anything is written;
     * - the bytes past the old end are written first, so running out of space happens before the old content is touched.
     */
    private fun writeInPlace(path: Path, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
            val lock = try {
                channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                null
            } ?: throw FileErrors.lockViolation(path)

            lock.use {
                val oldSize = channel.size()
                val overwritten = minOf(bytes.size.toLong(), oldSize).toInt()
                if (bytes.size > overwritten) {
                    try {
                        writeFully(channel, ByteBuffer.wrap(bytes, overwritten, bytes.size - overwritten), oldSize)
                    } catch (e: IOException) {
                        runCatching { channel.truncate(oldSize) }
                        throw e
                    }
                }
                writeFully(channel, ByteBuffer.wrap(bytes, 0, overwritten), 0)
                channel.truncate(bytes.size.toLong())
            }
        }
    }

    private fun writeFully(channel: FileChannel, buffer: ByteBuffer, position: Long) {
        var at = position
        while (buffer.hasRemaining()) at += channel.write(buffer, at)
    }

    fun createFolder(user: Principal, rawPath: FilePath): Result<Unit> {
        val rawParentPath = FilePath.ofAlreadyNormalized(rawPath.path.parent)

        // Get folder parent path
        val canonicalParentResult = resolvePath(rawParentPath, state.app.followSymlinks)
        if (canonicalParentResult.isNotSuccessful) return canonicalParentResult.cast()
        val canonicalParent = canonicalParentResult.value

        // Check permissions
        fileService.isAllowedToEditFile(user, canonicalParent).let {
            if (it.isNotSuccessful) return it.cast()
        }

        val lock = fileLockService.getLock(canonicalParent.path, LockType.READ)
        if (!lock.successful) return Result.reject("This folder is currently being modified.")

        try {
            // Get folder canonical path
            val folderName = rawPath.path.fileName
            val canonicalPath = FilePath.ofAlreadyNormalized(canonicalParent.path.resolve(folderName))

            // Check if folder already exists
            val alreadyExists = filesystemService.exists(canonicalPath.path, false)
            if (alreadyExists) return Result.reject("This folder already exists.")

            // Create folder
            filesystemService.createFolder(canonicalPath).let {
                if (it.isNotSuccessful) return it.cast()
            }

            fileService.ensureEntityIndexed(
                path = canonicalPath,
                ownerId = user.userId,
                userAction = UserAction.CREATE_FOLDER,
                reusePathEntity = false,
            ).let {
                if (it.isNotSuccessful) return it.cast()
            }

            return Result.ok()
        } finally {
            lock.unlock()
        }
    }

    fun createBlankFile(user: Principal, rawPath: FilePath): Result<FullFileMetadata> {
        val rawParentPath = FilePath.ofAlreadyNormalized(rawPath.path.parent)

        // Get folder parent path
        val canonicalParentResult = resolvePath(rawParentPath, state.app.followSymlinks)
        if (canonicalParentResult.isNotSuccessful) return canonicalParentResult.cast()
        val canonicalParent = canonicalParentResult.value

        // Check permissions
        fileService.isAllowedToEditFile(user, canonicalParent).let {
            if (it.isNotSuccessful) return it.cast()
        }

        val lock = fileLockService.getLock(canonicalParent.path, LockType.READ)
        if (!lock.successful) return Result.reject("This folder is currently being modified.")

        try {
            // Get folder canonical path
            val filename = rawPath.path.fileName
            val canonicalPath = FilePath.ofAlreadyNormalized(canonicalParent.path.resolve(filename))

            // Check if folder already exists
            val alreadyExists = filesystemService.exists(canonicalPath.path, false)
            if (alreadyExists) return Result.reject("This file already exists.")

            // Create folder
            filesystemService.createBlankFile(canonicalPath).let {
                if (it.isNotSuccessful) return it.cast()
            }

            fileService.ensureEntityIndexed(
                path = canonicalPath,
                ownerId = user.userId,
                userAction = UserAction.CREATE_FILE,
                reusePathEntity = false,
            ).let {
                if (it.isNotSuccessful) return it.cast()
            }

            val newMeta = fileService.getFullMetadata(user, canonicalPath, canonicalPath).let {
                if (it.hasError) return Result.error("File was created, but failed to load file metadata: ${it.error}")
                if (it.isNotSuccessful) return Result.reject(it.errorOrNull ?: "File was created, but failed to load file metadata. No error provided.")
                it.value
            }

            return Result.ok(newMeta)
        } finally {
            lock.unlock()
        }
    }
}
