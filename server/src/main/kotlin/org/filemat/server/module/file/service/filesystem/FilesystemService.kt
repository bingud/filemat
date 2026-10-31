package org.filemat.server.module.file.service.filesystem

import org.filemat.server.common.model.Result
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.FileErrors
import org.filemat.server.common.util.FileFailure
import org.filemat.server.common.util.reachesNetwork
import org.filemat.server.config.platform.FileIdentity
import org.filemat.server.config.platform.LinkInspector
import org.filemat.server.config.platform.PathPolicy
import org.filemat.server.config.platform.Platform
import org.filemat.server.module.file.model.FileMetadata
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FileType
import org.filemat.server.module.file.service.filesystem.fileOperation.*
import org.springframework.stereotype.Service
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlin.io.path.*


/**
 * Service that provides low level filesystem access
 *
 * For easier unit tests
 */
@Service
class FilesystemService(
    private val filesystemDeletionService: FilesystemDeletionService,
    private val filesystemMoveService: FilesystemMoveService,
    private val filesystemCopyService: FilesystemCopyService,
) : FilesystemDeletionOperations by filesystemDeletionService,
    FilesystemMoveOperations by filesystemMoveService,
    FilesystemCopyOperations by filesystemCopyService {

    fun getSize(canonicalPath: FilePath): Result<Long> {
        try {
            return canonicalPath.path.fileSize().toResult()
        }catch (e: NoSuchFileException) {
            return Result.notFound()
        } catch (e: Exception) {
            return Result.error("Failed to get file size.", exception = e)
        }
    }

    fun createFolder(folder: FilePath): Result<Unit> {
        if (!folder.path.isAbsolute) return Result.reject("Cannot create a folder here.")
        return try {
            Files.createDirectories(folder.path)
            Result.ok()
        } catch (e: Exception) {
            Result.error("Failed to create folder.", exception = e)
        }
    }

    fun createBlankFile(path: FilePath): Result<Unit> {
        if (!path.path.isAbsolute) return Result.reject("Cannot create a file here.")
        return try {
            Files.createFile(path.path)
            Result.ok()
        } catch (e: Exception) {
            Result.error("Failed to create file.", exception = e)
        }
    }

    fun replaceFileContentsAtomically(source: FilePath, destination: FilePath): Result<Unit> {
        // Read-only files stay read-only (Q38). Checked first, so the upload isn't copied for nothing.
        if (FileErrors.isReadOnlyFile(destination.path)) return FileErrors.resultOf(FileFailure.READ_ONLY)

        val replacementTemp = destination.path.resolveSibling(".filemat-replace-${destination.path.fileName}-${UUID.randomUUID()}.tmp")
        // Access denied while the temp file is made or filled is a missing right. Only the swap of an open file looks like it (§6.1).
        var isSwapping = false

        return try {
            // Keep the original TUS source in place until the destination swap succeeds.
            // If the final move fails, the caller can still clean up or retry with source intact.
            Files.createFile(replacementTemp)
            // Windows shows dot files, so the temp file is hidden while the upload is copied
            setHidden(replacementTemp, true)
            Files.newOutputStream(replacementTemp, StandardOpenOption.TRUNCATE_EXISTING).use { Files.copy(source.path, it) }
            setHidden(replacementTemp, false)

            isSwapping = true
            FileErrors.retryWhenInUse({ FileErrors.isTargetInUse(it, destination.path) }) {
                try {
                    Files.move(
                        replacementTemp,
                        destination.path,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(replacementTemp, destination.path, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            runCatching { Files.deleteIfExists(source.path) }

            Result.ok()
        } catch (e: NoSuchFileException) {
            Result.notFound()
        } catch (e: Exception) {
            if (if (isSwapping) FileErrors.isTargetInUse(e, destination.path) else FileErrors.isInUse(e)) return FileErrors.resultOf(FileFailure.IN_USE)
            if (e is AccessDeniedException) return Result.reject("Missing permission to replace file.")
            Result.error("Failed to replace file contents.", exception = e)
        } finally {
            runCatching { Files.deleteIfExists(replacementTemp) }
        }
    }

    private fun setHidden(path: Path, hidden: Boolean) {
        if (!Platform.isWindows) return
        runCatching { Files.setAttribute(path, "dos:hidden", hidden, LinkOption.NOFOLLOW_LINKS) }
    }

    /**
     * Returns whether file keys are stable on the filesystem of a file. Null when the file does not exist.
     */
    fun isSupportedFilesystem(path: FilePath): Boolean? {
        return FileIdentity.isSupported(path.path)
    }

    /**
     * Gets metadata for a file.
     */
    fun getMetadata(path: FilePath): FileMetadata? {
        if (PathPolicy.isVirtualRoot(path.path)) return virtualRootMetadata()

        val attributes: BasicFileAttributes = readAttributes(path.path, followSymbolicLinks = false)
            ?: return null

        val linkKind = LinkInspector.kindOf(path.path, attributes)
        // A link to a network folder is never followed, not even to tell a folder from a file (§4.3)
        val isNetworkLink = attributes.isSymbolicLink && reachesNetwork(path.path)
        val type = when {
            isNetworkLink -> FileType.FILE_LINK
            attributes.isRegularFile -> FileType.FILE
            // Junctions and volume mount points
            attributes.isDirectory && linkKind == LinkInspector.Kind.LINK -> FileType.FOLDER_LINK
            attributes.isDirectory -> FileType.FOLDER
            attributes.isSymbolicLink -> {
                val target = Files.readSymbolicLink(path.path)
                val resolved = path.path.parent.resolve(target).normalize()
                if (Files.isDirectory(resolved)) FileType.FOLDER_LINK else FileType.FILE_LINK
            }
            // OneDrive placeholders and deduplicated files (Q42)
            linkKind == LinkInspector.Kind.DATA -> FileType.FILE
            else -> FileType.OTHER
        }
        val creationTime = attributes.creationTime().toMillis()
        val modificationTime = attributes.lastModifiedTime().toMillis()

        return FileMetadata(
            path = path.pathString,
            modifiedDate = modificationTime,
            createdDate = creationTime,
            fileType = type,
            size = attributes.size(),
            isExecutable = !isNetworkLink && Files.isExecutable(path.path),
            isWritable = !isNetworkLink && Files.isWritable(path.path),
        )
    }

    /**
     * The Windows drive list, shown as a read-only folder
     */
    private fun virtualRootMetadata() = FileMetadata(
        path = PathPolicy.root,
        modifiedDate = 0,
        createdDate = 0,
        fileType = FileType.FOLDER,
        size = 0,
        isExecutable = true,
        isWritable = false,
    )

    /**
     * Returns basic file attributes of path (one call per file on every OS)
     */
    fun readAttributes(path: Path, followSymbolicLinks: Boolean): BasicFileAttributes? {
        return runCatching {
            if (followSymbolicLinks) {
                Files.readAttributes(path, BasicFileAttributes::class.java)
            } else {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            }
        }.getOrNull()
    }

    /**
     * Returns whether input file path exists
     */
    fun exists(path: Path, followSymbolicLinks: Boolean) = if (followSymbolicLinks) path.exists() else path.exists(LinkOption.NOFOLLOW_LINKS)

    fun isFolderEmpty(path: Path, ignoreEmpty: Boolean = false): Result<Boolean> {
        try {
            if (ignoreEmpty) {
                if (!Files.exists(path)) return Result.ok(true)
            }

            if (!Files.isDirectory(path)) {
                return Result.ok(false)
            }
            Files.newDirectoryStream(path).use { stream ->
                return Result.ok(stream.iterator().hasNext() == false)
            }
        } catch (e: Exception) {
            return Result.error("Failed to check if folder is empty.", exception = e)
        }
    }

    /**
     * Walks over immediate children of the input path.
     */
    fun walkChildren(path: FilePath, onFile: (Path) -> Unit): Result<Unit> {
        return try {
            Files.newDirectoryStream(path.path).use { stream ->
                stream.forEach { onFile(it) }
            }
            Result.ok()
        } catch (e: NotDirectoryException) {
            Result.error("Path is not a directory.")
        } catch (e: NoSuchFileException) {
            Result.notFound()
        } catch (e: Exception) {
            Result.error("Failed to walk directory.", exception = e)
        }
    }

    /**
     * Returns the file key of a file or link (links are not followed)
     */
    fun getFileKey(path: Path) = FileIdentity.fileKey(path)

}