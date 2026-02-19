package org.filemat.server.module.file.service.file.component

import org.apache.commons.io.input.BoundedInputStream
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.StringUtils
import org.filemat.server.common.util.getPathRelationship
import org.filemat.server.common.util.resolvePath
import org.filemat.server.common.util.safeStreamSkip
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FullFileMetadata
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.LockType
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Service
class FileContentService(
    private val fileService: FileService,
    private val fileLockService: FileLockService,
    private val filesystemService: FilesystemService,
    private val entityService: EntityService
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
        val canonicalPath = existingCanonicalPath ?: resolvePath(rawPath).let { path ->
            if (path.isNotSuccessful) {
                return path.cast()
            }
            path.value
        }

        if (!ignorePermissions) {
            fileService.isAllowedToAccessFile(user, canonicalPath).let {
                if (it.isNotSuccessful) return it.cast()
            }
        }

        if (!Files.isRegularFile(canonicalPath.path, LinkOption.NOFOLLOW_LINKS)) return Result.notFound()

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
        // 1. Resolve Initial Path
        val canonicalPathResult = fileService.resolvePathWithOptionalShare(
            path = rawPath,
            shareToken = shareToken,
            withPathContainsSymlink = true
        )

        val canonicalPath = canonicalPathResult.let {
            if (it.isNotSuccessful) return
            it.value
        }

        // 2. Setup Protection and Config
        val isReadDataFolderProtected = State.App.allowReadDataFolder == false
        val copyResolvedSymlinks = State.App.followSymlinks

        // Cannot zip FROM protected data folder (Read protection)
        if (isReadDataFolderProtected) {
            val sourceRelation = getPathRelationship(path = canonicalPath.path, target = Props.dataFolderPath)
            if (sourceRelation.isInsideTarget) {
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
            protectedPath = if (isReadDataFolderProtected) Props.dataFolderPath else null,
            ignorePermissions = isShared,
            copyResolvedSymlinks = copyResolvedSymlinks
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
    ): Int {
        var failedCount = 0
        val sourceFilePath = FilePath.ofAlreadyNormalized(currentSource)

        // Explicit protection check
        if (protectedPath != null && currentSource == protectedPath) return 1

        val isSymlink = Files.isSymbolicLink(currentSource)
        if (isSymlink && !copyResolvedSymlinks) return 0

        // 1. Determine Type (Dir vs Symlink)
        val isDirectory = if (copyResolvedSymlinks) {
            Files.isDirectory(currentSource)
        } else {
            Files.isDirectory(currentSource, LinkOption.NOFOLLOW_LINKS)
        }

        // 2. Resolve Symlink and Check for Loops
        val resolvedPath = if (isSymlink == true) {
            resolvePath(sourceFilePath).let { result ->
                if (result.isNotSuccessful) return failedCount + 1
                result.value
            }.also {
                // Loop prevention: checks if source contains target or target contains source
                if (currentSource.startsWith(it.path)) return failedCount + 1
                if (it.path.startsWith(currentSource)) return failedCount + 1
            }
        } else null

        return fileLockService.tryWithLock(
            currentSource to LockType.READ,
            resolvedPath?.path to LockType.READ,
        ) {
            // 3. Handle Directory Recursion
            if (isDirectory) {
                try {
                    // Add directory entry to Zip (must end in /)
                    if (currentZipPath != null) {
                        val dirEntryName = currentZipPath.toString().let { if (it.endsWith("/")) it else "$it/" }
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
                                copyResolvedSymlinks = copyResolvedSymlinks
                            )
                        }
                    }
                } catch (e: Exception) {
                    return@tryWithLock failedCount + 1
                }
                return@tryWithLock failedCount
            }

            // 4. Permission Checks
            if (isSymlink == true) {
                if (resolvedPath == null) return@tryWithLock failedCount + 1

                fileService.isAllowedToAccessFile(
                    user = user,
                    canonicalPath = resolvedPath,
                    ignorePermissions = ignorePermissions
                ).let { if (it.isNotSuccessful) return@tryWithLock failedCount + 1 }

            } else {
                fileService.isAllowedToAccessFile(
                    user = user,
                    canonicalPath = sourceFilePath,
                    ignorePermissions = ignorePermissions
                ).let { if (it.isNotSuccessful) return@tryWithLock failedCount + 1 }
            }

            // 5. Write File to Zip
            try {
                val entryName = currentZipPath?.toString() ?: currentSource.fileName.toString()
                zip.putNextEntry(ZipEntry(entryName))

                val inputOptions = if (copyResolvedSymlinks) emptyArray() else arrayOf(LinkOption.NOFOLLOW_LINKS)

                // Use standard InputStream. getFileContent is not needed as we did manual perm checks above
                Files.newInputStream(currentSource, *inputOptions).use { inputStream ->
                    BufferedInputStream(inputStream).copyTo(zip)
                }
                zip.closeEntry()
            } catch (e: Exception) {
                return@tryWithLock failedCount + 1
            }

            return@tryWithLock failedCount
        }.onFailure { failedCount + 1 }
    }

    data class EditFileResult(val modifiedDate: Long, val size: Long)
    fun editFile(user: Principal, rawPath: FilePath, newContent: String): Result<EditFileResult> {
        val canonicalResult = resolvePath(rawPath)
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
                file.writeText(newContent)
            } catch (e: Exception) {
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

    fun createFolder(user: Principal, rawPath: FilePath): Result<Unit> {
        val rawParentPath = FilePath.ofAlreadyNormalized(rawPath.path.parent)

        // Get folder parent path
        val canonicalParentResult = resolvePath(rawParentPath)
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

            entityService.create(
                canonicalPath = canonicalPath,
                ownerId = user.userId,
                userAction = UserAction.CREATE_FOLDER,
            )

            return Result.ok()
        } finally {
            lock.unlock()
        }
    }

    fun createBlankFile(user: Principal, rawPath: FilePath): Result<FullFileMetadata> {
        val rawParentPath = FilePath.ofAlreadyNormalized(rawPath.path.parent)

        // Get folder parent path
        val canonicalParentResult = resolvePath(rawParentPath)
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

            entityService.create(
                canonicalPath = canonicalPath,
                ownerId = user.userId,
                userAction = UserAction.CREATE_FILE,
            )

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