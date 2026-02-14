package org.filemat.server.module.file.service.file

import kotlinx.coroutines.flow.Flow
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.util.*
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.*
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.file.service.file.component.*
import org.filemat.server.module.file.service.file.component.FileContentService.EditFileResult
import org.filemat.server.module.permission.model.FilePermission
import org.filemat.server.module.user.model.UserAction
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.io.InputStream
import java.nio.file.Path
import java.util.zip.ZipOutputStream


/**
 * Service to interact with files.
 */
@Service
class FileService(
    private val entityService: EntityService,
    @Lazy private val fileContentService: FileContentService,
    @Lazy private val fileMoveService: FileMoveService,
    @Lazy private val fileCopyService: FileCopyService,
    @Lazy private val fileDeletionService: FileDeletionService,
    @Lazy private val fileFolderEntriesService: FileFolderEntriesService,
    @Lazy private val fileSecurityService: FileSecurityService,
    @Lazy private val fileMetadataService: FileMetadataService,
    @Lazy private val fileEntryListsService: FileEntryListsService,
) {

    // --- File Content operations ---

    fun addFileToZip(
        zip: ZipOutputStream,
        rawPath: FilePath,
        existingBaseZipPath: Path?,
        principal: Principal?,
        shareToken: String?
    ) = fileContentService.addFileToZip(
        zip = zip,
        rawPath = rawPath,
        existingBaseZipPath = existingBaseZipPath,
        principal = principal,
        shareToken = shareToken
    )

    fun getFileContent(
        user: Principal?,
        rawPath: FilePath,
        existingCanonicalPath: FilePath? = null,
        existingPathContainsSymlink: Boolean? = false,
        range: LongRange? = null,
        ignorePermissions: Boolean = false
    ): Result<InputStream> = fileContentService.getFileContent(
        user = user,
        rawPath = rawPath,
        existingCanonicalPath = existingCanonicalPath,
        existingPathContainsSymlink = existingPathContainsSymlink,
        range = range,
        ignorePermissions = ignorePermissions,
    )

    fun createFolder(user: Principal, rawPath: FilePath): Result<Unit>
            = fileContentService.createFolder(user = user, rawPath = rawPath)

    fun createBlankFile(user: Principal, rawPath: FilePath): Result<FullFileMetadata>
             = fileContentService.createBlankFile(user, rawPath)

    // --- File Operations ---

    fun moveFile(user: Principal, rawPath: FilePath, rawNewPath: FilePath): Result<Unit>
            = fileMoveService.moveFile(user = user, rawPath = rawPath, rawDestinationPath = rawNewPath)

    fun moveMultipleFiles(user: Principal, rawPaths: List<FilePath>, rawNewParentPath: FilePath): Result<List<FilePath>>
            = fileMoveService.moveMultipleFiles(user = user, rawPaths = rawPaths, rawNewParentPath = rawNewParentPath)

    fun copyFile(user: Principal, rawPath: FilePath, rawDestinationPath: FilePath): Result<FullFileMetadata>
            = fileCopyService.copyFile(user = user, rawPath = rawPath, rawDestinationPath = rawDestinationPath)

    fun editFile(user: Principal, rawPath: FilePath, newContent: String): Result<EditFileResult>
            = fileContentService.editFile(user = user, rawPath = rawPath, newContent = newContent)

    // --- Folder Entries ---

    /**
     * @return List of successfully deleted paths
     */
    fun deleteFiles(user: Principal, rawPathList: List<FilePath>): List<FilePath> = fileDeletionService.deleteFiles(user = user, rawPathList = rawPathList)

    fun <T : AbstractFileMetadata> getFolderEntries(
        user: Principal?,
        canonicalPath: FilePath,
        foldersOnly: Boolean = false,
        ignorePermissions: Boolean = false,
        metaMapper: (meta: FileMetadata, getFull: (meta: FileMetadata) -> FullFileMetadata?) -> T?
    ): Result<List<T>> = fileFolderEntriesService.getFolderEntries(
        user = user,
        canonicalPath = canonicalPath,
        foldersOnly = foldersOnly,
        ignorePermissions = ignorePermissions,
        metaMapper = metaMapper
    )

    fun getFolderEntries(
        user: Principal?,
        canonicalPath: FilePath,
        foldersOnly: Boolean = false,
        ignorePermissions: Boolean = false,
    ): Result<List<FullFileMetadata>> = fileFolderEntriesService.getFolderEntries(user = user, canonicalPath = canonicalPath, foldersOnly = foldersOnly, ignorePermissions = ignorePermissions)

    // --- Metadata ---

    fun getFileOrFolderEntries(user: Principal, rawPath: FilePath, foldersOnly: Boolean = false): Result<Pair<FullFileMetadata, List<FullFileMetadata>?>>
            = fileMetadataService.getFileOrFolderEntries(user = user, rawPath = rawPath, foldersOnly = foldersOnly)

    fun getSharedFileOrFolderEntries(rawPath: FilePath, foldersOnly: Boolean = false, shareToken: String): Result<Pair<FileMetadata, List<FileMetadata>?>>
            = fileMetadataService.getSharedFileOrFolderEntries(rawPath = rawPath, foldersOnly = foldersOnly, shareToken = shareToken)

    fun getFullMetadata(user: Principal?, rawPath: FilePath, canonicalPath: FilePath, ignorePermissions: Boolean = false): Result<FullFileMetadata>
            = fileMetadataService.getFullMetadata(user = user, rawPath = rawPath, canonicalPath = canonicalPath, ignorePermissions = ignorePermissions)

    fun getMetadata(user: Principal?, rawPath: FilePath, isPathCanonical: Boolean = false): Result<FileMetadata>
            = fileMetadataService.getMetadata(user = user, rawPath = rawPath, isPathCanonical = isPathCanonical)

    // --- Entry lists ---

    fun searchFiles(
        user: Principal?,
        canonicalPath: FilePath,
        text: String,
        isShared: Boolean = false,
        userAction: UserAction
    ): Flow<Result<FullFileMetadata>> = fileEntryListsService.searchFiles(
        user = user,
        canonicalPath = canonicalPath,
        text = text,
        isShared = isShared,
        userAction = userAction
    )

    fun getPermittedFileList(user: Principal): Result<List<FullFileMetadata>>
             = fileEntryListsService.getPermittedFileList(user = user)

    fun getSharedFileList(user: Principal, getAll: Boolean, userAction: UserAction): Result<List<FullFileMetadata>>
            = fileEntryListsService.getSharedFileList(user = user, getAll = getAll, userAction = userAction)

    // --- File security ---

    fun getActualFilePermissions(user: Principal, canonicalPath: FilePath): Set<FilePermission>
            = fileSecurityService.getActualFilePermissions(user = user, canonicalPath = canonicalPath)

    fun isAllowedToAccessFile(user: Principal?, canonicalPath: FilePath, checkPermissionOnly: Boolean = false, ignorePermissions: Boolean? = null): Result<Unit>
            = fileSecurityService.isAllowedToAccessFile(user = user, canonicalPath = canonicalPath, checkPermissionOnly = checkPermissionOnly, ignorePermissions = ignorePermissions)

    fun isAllowedToEditFile(user: Principal, canonicalPath: FilePath, ignorePermissions: Boolean? = null): Result<Unit>
            = fileSecurityService.isAllowedToEditFile(user = user, canonicalPath = canonicalPath, ignorePermissions = ignorePermissions)

    fun isAllowedToMoveFile(user: Principal, canonicalPath: FilePath, ignorePermissions: Boolean? = null): Result<Unit>
            = fileSecurityService.isAllowedToMoveFile(user = user, canonicalPath = canonicalPath, ignorePermissions = ignorePermissions)

    fun isAllowedToRenameFile(user: Principal, canonicalPath: FilePath, ignorePermissions: Boolean? = null): Result<Unit>
            = fileSecurityService.isAllowedToRenameFile(user = user, canonicalPath = canonicalPath, ignorePermissions = ignorePermissions)

    fun isAllowedToDeleteFile(user: Principal, canonicalPath: FilePath, ignorePermissions: Boolean? = null): Result<Unit>
            = fileSecurityService.isAllowedToDeleteFile(user = user, canonicalPath = canonicalPath, ignorePermissions = ignorePermissions)

    fun isAllowedToShareFile(user: Principal, canonicalPath: FilePath, ignorePermissions: Boolean? = null): Result<Unit>
            = fileSecurityService.isAllowedToShareFile(user = user, canonicalPath = canonicalPath, ignorePermissions = ignorePermissions)

    fun hasAdminAccess(user: Principal): Boolean = fileSecurityService.hasAdminAccess(user = user)

    fun verifyEntityInode(path: FilePath, userAction: UserAction): Result<Unit>
            = fileSecurityService.verifyEntityInode(path = path, userAction = userAction)

    // --- Utilities ---

    fun resolvePathWithOptionalShare(path: FilePath, shareToken: String?, withPathContainsSymlink: Boolean): Pair<Result<FilePath>, Boolean> {
        val sharedPath = if (shareToken != null) {
            entityService.getByShareToken(shareToken = shareToken)
                .let {
                    if (it.isNotSuccessful) return Pair(it.cast(), false)

                    val sharePathStr = it.value.path ?: return Pair(Result.notFound(), false)
                    val sharePath = FilePath.of(sharePathStr)
                    val fullPath = sharePath.path.resolve(path.pathString.removePrefix("/"))
                    return@let FilePath.ofAlreadyNormalized(fullPath)
                }
        } else null

        return resolvePath(sharedPath ?: path)
    }

    fun resolvePathWithOptionalShare(path: FilePath, shareToken: String?): Result<FilePath> {
        val result = resolvePathWithOptionalShare(path, shareToken, true)
        return result.first
    }
}