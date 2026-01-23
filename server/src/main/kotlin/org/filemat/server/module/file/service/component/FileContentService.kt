package org.filemat.server.module.file.service.component

import org.filemat.server.common.State
import org.filemat.server.common.util.getPathRelationship
import org.filemat.server.common.util.resolvePath
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.FileService
import org.springframework.stereotype.Service
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Service
class FileContentService(
    private val fileService: FileService,
    private val fileLockService: FileLockService
) {

    fun addFileToZip(
        zip: ZipOutputStream,
        rawPath: FilePath,
        existingBaseZipPath: Path?,
        principal: Principal?,
        shareToken: String?
    ) {
        val isShared = shareToken != null
        // 1. Resolve Initial Path
        val (canonicalPathResult, _) = fileService.resolvePathWithOptionalShare(
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
            resolvePath(sourceFilePath).let { (result, _) ->
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

}