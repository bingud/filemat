package org.filemat.server.module.file.service.filesystem

import me.desair.tus.server.TusFileUploadService
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.FileUtils
import org.filemat.server.module.file.model.FileMetadata
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FileType
import org.filemat.server.module.file.service.filesystem.fileOperation.*
import org.springframework.stereotype.Service
import java.nio.file.*
import java.nio.file.attribute.PosixFileAttributes
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

    final var tusFileService: TusFileUploadService? = null
        private set

    fun getSize(canonicalPath: FilePath): Result<Long> {
        try {
            return canonicalPath.path.fileSize().toResult()
        }catch (e: NoSuchFileException) {
            return Result.notFound()
        } catch (e: Exception) {
            return Result.error("Failed to get file size.")
        }
    }

    fun initializeTusService() {
        tusFileService = TusFileUploadService()
            .withUploadUri("/api/v1/file/upload")
            .withStoragePath(State.App.uploadFolderPath)
    }

    fun createFolder(folder: FilePath): Result<Unit> {
        return try {
            Files.createDirectories(folder.path)
            Result.ok()
        } catch (e: Exception) {
            Result.error("Failed to create folder.")
        }
    }

    /**
     * Returns whether file is in a supported filesystem
     */
    fun isSupportedFilesystem(path: FilePath): Boolean? {
        return FileUtils.isSupportedFilesystem(path.path)
    }

        /**
     * Gets metadata for a file.
     */
    fun getMetadata(path: FilePath): FileMetadata? {
        val attributes: PosixFileAttributes = readAttributes(path.path, followSymbolicLinks = false)
            ?: return null

        val type = when {
            attributes.isRegularFile -> FileType.FILE
            attributes.isDirectory -> FileType.FOLDER
            attributes.isSymbolicLink -> {
                val target = Files.readSymbolicLink(path.path)
                val resolved = path.path.parent.resolve(target).normalize()
                if (Files.isDirectory(resolved)) FileType.FOLDER_LINK else FileType.FILE_LINK
            }
            else -> FileType.OTHER
        }
        val creationTime = attributes.creationTime().toMillis()
        val modificationTime = attributes.lastModifiedTime().toMillis()

        return FileMetadata(
            path = path.path.absolutePathString(),
            modifiedDate = modificationTime,
            createdDate = creationTime,
            fileType = type,
            size = attributes.size(),
            isExecutable = if (State.App.followSymlinks) Files.isExecutable(path.path) else true,
            isWritable = if (State.App.followSymlinks) Files.isWritable(path.path) else false,
        )
    }

    /**
     * Returns basic file attributes of path
     */
    fun readAttributes(path: Path, followSymbolicLinks: Boolean): PosixFileAttributes? {
        return runCatching {
            if (followSymbolicLinks) {
                Files.readAttributes(path, PosixFileAttributes::class.java)
            } else {
                Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            }
        }.getOrNull()
    }

    /**
     * Returns whether input file path exists
     */
    fun exists(path: Path, followSymbolicLinks: Boolean) = if (followSymbolicLinks) path.exists() else path.exists(LinkOption.NOFOLLOW_LINKS)

    /**
     * Returns inode number for input path
     */
    fun getInode(path: Path, followSymbolicLinks: Boolean) = FileUtils.getInode(path, followSymbolicLinks)

}