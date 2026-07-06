package org.filemat.server.module.file.service

import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.util.getFilenameFromPath
import org.filemat.server.common.util.resolvePath
import org.filemat.server.common.util.splitByLast
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.*
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.file.ThumbnailService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.user.model.UserAction
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

private enum class FolderUploadPlatform {
    LINUX,
    WINDOWS,
}

data class FolderUploadFinalizeResult(
    val actualFilename: String?,
    val skipped: Boolean = false,
)

/**
 * Coordinates folder uploads around the existing per-file TUS pipeline.
 *
 * Browser-relative paths remain strings until they are resolved under a real
 * Filemat destination. From that point on, the service passes [FilePath] around
 * so upload behavior matches the rest of the backend.
 */
@Service
class FolderUploadService(
    private val fileService: FileService,
    private val filesystemService: FilesystemService,
    private val entityService: EntityService,
    private val fileLockService: FileLockService,
    @Lazy private val thumbnailService: ThumbnailService,
) {
    // Keep platform-specific rules behind one switch so Windows support can be added
    // later without scattering OS checks through the upload flow.
    private val platform = FolderUploadPlatform.LINUX

    // Short-lived server-side plans. TUS requests send session id + relative path,
    // then the server looks up the trusted target path and resolution here.
    private val sessions = ConcurrentHashMap<String, FolderUploadSession>()

    private val sessionTtlMillis = 48L * 60L * 60L * 1000L
    private val maxFiles = 20_000
    private val maxDirectories = 20_000
    private val maxTotalBytes = 1024L * 1024L * 1024L * 1024L
    private val maxTotalPathBytes = 2L * 1024L * 1024L

    /**
     * Checks the complete folder manifest before any bytes are transferred.
     * This gives the UI conflicts/blocked paths up front, but final TUS writes
     * still re-check because the filesystem can change after preflight.
     */
    fun preflight(user: Principal, manifest: FolderUploadManifest): Result<FolderUploadPreflightResponse> {
        cleanupExpiredSessions()

        val normalized = normalizeManifest(manifest).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }
        validateLimits(normalized).let {
            if (it.isNotSuccessful) return it.cast()
        }

        val destinationParent = resolveDestinationParent(user, normalized.destinationPath).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }

        val conflicts = mutableListOf<FolderUploadConflict>()
        val blocked = mutableListOf<FolderUploadBlockedPath>()

        // Existing folders are merge targets. A file or non-directory where an
        // incoming directory must exist is a conflict the user has to resolve.
        normalized.directories.forEach { relativePath ->
            val target = resolveUploadTarget(destinationParent, relativePath)
            if (target == null) {
                blocked += blocked(relativePath, target, "Path escapes the destination folder.")
                return@forEach
            }

            val existingType = existingType(target)
            when (existingType) {
                null,
                FolderUploadEntryType.DIRECTORY -> Unit
                FolderUploadEntryType.FILE,
                FolderUploadEntryType.OTHER -> conflicts += FolderUploadConflict(
                    relativePath = relativePath,
                    targetPath = target.toString(),
                    incomingType = FolderUploadEntryType.DIRECTORY,
                    existingType = existingType,
                    allowedResolutions = listOf(FolderUploadResolution.SKIP, FolderUploadResolution.KEEP_BOTH),
                )
            }
        }

        // File conflicts are resolved per file. File-vs-directory conflicts stay
        // non-destructive in v1: users can skip or keep both, not replace a folder.
        normalized.files.forEach { file ->
            val target = resolveUploadTarget(destinationParent, file.relativePath)
            if (target == null) {
                blocked += blocked(file.relativePath, target, "Path escapes the destination folder.")
                return@forEach
            }

            val existingType = existingType(target)
            when (existingType) {
                null -> Unit
                FolderUploadEntryType.FILE,
                FolderUploadEntryType.OTHER -> conflicts += FolderUploadConflict(
                    relativePath = file.relativePath,
                    targetPath = target.toString(),
                    incomingType = FolderUploadEntryType.FILE,
                    existingType = existingType,
                    allowedResolutions = listOf(FolderUploadResolution.OVERWRITE, FolderUploadResolution.SKIP, FolderUploadResolution.KEEP_BOTH),
                )
                FolderUploadEntryType.DIRECTORY -> conflicts += FolderUploadConflict(
                    relativePath = file.relativePath,
                    targetPath = target.toString(),
                    incomingType = FolderUploadEntryType.FILE,
                    existingType = existingType,
                    allowedResolutions = listOf(FolderUploadResolution.SKIP, FolderUploadResolution.KEEP_BOTH),
                )
            }
        }

        blocked += permissionBlocks(user, destinationParent, normalized)

        return Result.ok(
            FolderUploadPreflightResponse(
                conflicts = conflicts.distinctBy { it.relativePath },
                blocked = blocked.distinctBy { it.relativePath },
                summary = normalized.summary,
            )
        )
    }

    /**
     * Re-validates the manifest and stores a trusted upload plan. This is what
     * prevents individual TUS requests from changing target paths or conflict choices.
     */
    fun createSession(user: Principal, request: FolderUploadSessionRequest): Result<FolderUploadSessionResponse> {
        cleanupExpiredSessions()

        val normalized = normalizeManifest(request.manifest).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }
        validateLimits(normalized).let {
            if (it.isNotSuccessful) return it.cast()
        }

        val destinationParent = resolveDestinationParent(user, normalized.destinationPath).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }

        val preflight = preflight(user, request.manifest).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }
        if (preflight.blocked.isNotEmpty()) {
            return Result.reject("Some files cannot be uploaded because they are blocked or missing permissions.")
        }

        val resolutionByRelativePath = request.resolutions.associate { it.relativePath to it.resolution }
        val conflictByRelativePath = preflight.conflicts.associateBy { it.relativePath }
        val skippedFiles = mutableListOf<String>()
        val queuedFiles = mutableListOf<FolderUploadSessionFile>()
        val createdDirectories = mutableListOf<String>()
        val directoryRemaps = mutableListOf<DirectoryRemap>()
        val skippedPrefixes = mutableListOf<String>()

        // Directory-level decisions affect whole subtrees. A keep-both decision
        // remaps the entire incoming branch to the numbered destination folder.
        preflight.conflicts
            .filter { it.incomingType == FolderUploadEntryType.DIRECTORY }
            .sortedBy { it.relativePath.length }
            .forEach { conflict ->
                val resolution = resolutionByRelativePath[conflict.relativePath]
                    ?: request.defaultResolution
                    ?: return Result.reject("Missing resolution for ${conflict.relativePath}.")

                if (!conflict.allowedResolutions.contains(resolution)) {
                    return Result.reject("Resolution $resolution is not allowed for ${conflict.relativePath}.")
                }

                when (resolution) {
                    FolderUploadResolution.SKIP -> skippedPrefixes += addSlash(conflict.relativePath)
                    FolderUploadResolution.KEEP_BOTH -> {
                        val target = resolveUploadTarget(destinationParent, conflict.relativePath)
                            ?: return Result.reject("Path escapes the destination folder.")
                        directoryRemaps += DirectoryRemap(conflict.relativePath, resolveKeepBothPath(target))
                    }
                    FolderUploadResolution.OVERWRITE -> return Result.reject("Cannot overwrite a file with a folder.")
                }
            }

        val targetDirectories = normalized.directories
            .filter { relativePath -> skippedPrefixes.none { prefix -> relativePath == prefix.removeSuffix("/") || relativePath.startsWith(prefix) } }
            .map { relativePath -> applyDirectoryRemaps(relativePath, destinationParent, directoryRemaps) }
            .distinct()
            .sortedBy { it.path.nameCount }

        // Create directories before queueing files, including empty folders from
        // the manifest. Existing directories are accepted to make concurrent uploads safe.
        targetDirectories.forEach { directoryPath ->
            ensureDirectory(user, directoryPath, createdDirectories).let {
                if (it.isNotSuccessful) return it.cast()
            }
        }

        val sessionFiles = mutableMapOf<String, FolderUploadSessionFilePlan>()
        normalized.files.forEach { file ->
            if (skippedPrefixes.any { file.relativePath.startsWith(it) }) {
                skippedFiles += file.relativePath
                return@forEach
            }

            val conflict = conflictByRelativePath[file.relativePath]
            val resolution = if (conflict != null) {
                resolutionByRelativePath[file.relativePath]
                    ?: request.defaultResolution
                    ?: return Result.reject("Missing resolution for ${file.relativePath}.")
            } else {
                request.defaultResolution ?: FolderUploadResolution.KEEP_BOTH
            }

            if (conflict != null && !conflict.allowedResolutions.contains(resolution)) {
                return Result.reject("Resolution $resolution is not allowed for ${file.relativePath}.")
            }

            if (resolution == FolderUploadResolution.SKIP) {
                skippedFiles += file.relativePath
                return@forEach
            }

            // Keep-both names are computed now for the session response, and again
            // under lock at final write time to handle races.
            val baseTarget = applyDirectoryRemaps(file.relativePath, destinationParent, directoryRemaps)
            val targetPath = if (resolution == FolderUploadResolution.KEEP_BOTH && baseTarget.exists(LinkOption.NOFOLLOW_LINKS)) {
                resolveKeepBothPath(baseTarget)
            } else {
                baseTarget
            }
            val parentPath = targetPath.path.parent?.let { FilePath.ofAlreadyNormalized(it) }
                ?: return Result.reject("Invalid target path for ${file.relativePath}.")
            ensureDirectory(user, parentPath, createdDirectories).let {
                if (it.isNotSuccessful) return it.cast()
            }

            val sessionFile = FolderUploadSessionFile(
                relativePath = file.relativePath,
                targetPath = targetPath.pathString,
                resolution = resolution,
            )
            queuedFiles += sessionFile
            sessionFiles[file.relativePath] = FolderUploadSessionFilePlan(
                relativePath = file.relativePath,
                targetPath = targetPath,
                resolution = resolution,
            )
        }

        val now = unixNowMillis()
        val session = FolderUploadSession(
            sessionId = UlidCreator.getUlid().toString(),
            userId = user.userId,
            destinationParent = destinationParent,
            files = sessionFiles,
            createdAt = now,
            expiresAt = now + sessionTtlMillis,
        )
        sessions[session.sessionId] = session

        return Result.ok(
            FolderUploadSessionResponse(
                sessionId = session.sessionId,
                queuedFiles = queuedFiles,
                skippedFiles = skippedFiles.distinct(),
                createdDirectories = createdDirectories.distinct(),
                expiresAt = session.expiresAt,
            )
        )
    }

    /**
     * Called from TUS POST before chunk upload starts. It rejects stale sessions,
     * wrong users, or relative paths that are not part of the stored plan.
     */
    fun validateTusUploadStart(user: Principal, sessionId: String, relativePath: String): Result<Unit> {
        val session = getSession(sessionId).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }
        if (session.userId != user.userId) return Result.reject("This upload session does not belong to the current user.")
        if (!session.files.containsKey(relativePath)) return Result.reject("This file is not part of the upload session.")
        return Result.ok(Unit)
    }

    /**
     * Called after TUS has assembled the temporary upload file. This is the final
     * race guard: lock the destination, re-check permission, and apply the stored policy.
     */
    fun finalizeTusUpload(user: Principal, sessionId: String, relativePath: String, uploadLocation: FilePath): Result<FolderUploadFinalizeResult> {
        val session = getSession(sessionId).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }
        if (session.userId != user.userId) return Result.reject("This upload session does not belong to the current user.")
        val plan = session.files[relativePath] ?: return Result.reject("This file is not part of the upload session.")

        val lock = fileLockService.getLock(plan.targetPath.path, LockType.WRITE)
        if (!lock.successful) return Result.reject("This file is currently being modified.")

        try {
            val destinationParent = FilePath.ofAlreadyNormalized(plan.targetPath.path.parent)
            fileService.isAllowedToEditFile(user, destinationParent).let {
                if (it.isNotSuccessful) return it.cast()
            }

            return when (plan.resolution) {
                FolderUploadResolution.SKIP -> Result.ok(FolderUploadFinalizeResult(actualFilename = null, skipped = true))
                FolderUploadResolution.KEEP_BOTH -> finalizeKeepBoth(user, uploadLocation, plan.targetPath)
                FolderUploadResolution.OVERWRITE -> finalizeOverwrite(user, uploadLocation, plan.targetPath)
            }
        } finally {
            lock.unlock()
        }
    }

    // Recompute the numbered name at write time so a concurrent upload cannot take
    // the candidate that was originally planned during session creation.
    private fun finalizeKeepBoth(user: Principal, uploadLocation: FilePath, plannedTarget: FilePath): Result<FolderUploadFinalizeResult> {
        val finalTarget = if (plannedTarget.exists(LinkOption.NOFOLLOW_LINKS)) {
            resolveKeepBothPath(plannedTarget)
        } else {
            plannedTarget
        }

        moveUploadedFile(user, uploadLocation, finalTarget).let {
            if (it.isNotSuccessful) return it.cast()
        }
        createEntityOrRollback(user, finalTarget).let {
            if (it.isNotSuccessful) return it.cast()
        }

        return Result.ok(FolderUploadFinalizeResult(actualFilename = getFilenameFromPath(finalTarget.path)))
    }

    // Overwrite preserves the existing Filemat entity. If the destination vanished
    // after preflight, treat it as a normal create at the intended path.
    private fun finalizeOverwrite(user: Principal, uploadLocation: FilePath, plannedTarget: FilePath): Result<FolderUploadFinalizeResult> {
        val exists = plannedTarget.exists(LinkOption.NOFOLLOW_LINKS)
        if (exists && !plannedTarget.path.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
            return Result.reject(CONFLICT_PREFIX + "Target is no longer a regular file.")
        }

        if (!exists) {
            moveUploadedFile(user, uploadLocation, plannedTarget).let {
                if (it.isNotSuccessful) return it.cast()
            }
            createEntityOrRollback(user, plannedTarget).let {
                if (it.isNotSuccessful) return it.cast()
            }
            return Result.ok(FolderUploadFinalizeResult(actualFilename = getFilenameFromPath(plannedTarget.path)))
        }

        filesystemService.replaceFileContentsAtomically(source = uploadLocation, destination = plannedTarget).let {
            if (it.isNotSuccessful) return it.cast()
        }
        updatePreservedOverwriteEntity(plannedTarget).let {
            if (it.isNotSuccessful) return it.cast()
        }
        thumbnailService.deleteCacheForPath(plannedTarget)

        return Result.ok(FolderUploadFinalizeResult(actualFilename = getFilenameFromPath(plannedTarget.path)))
    }

    // Atomic replacement can give the existing path a new inode. Keep the existing
    // entity, but update its inode immediately so later reads do not trigger repair
    // logic that tries to move a stale inode entity onto this occupied path.
    private fun updatePreservedOverwriteEntity(canonicalPath: FilePath): Result<Unit> {
        val pathEntity = entityService.getByPath(canonicalPath.pathString, UserAction.UPLOAD_FILE).let {
            if (it.notFound) return Result.ok(Unit)
            if (it.isNotSuccessful) return it.cast()
            it.value
        }

        val newInode = filesystemService.getInode(canonicalPath.path, followSymbolicLinks = false)
            ?: return Result.ok(Unit)
        if (pathEntity.inode == newInode) return Result.ok(Unit)

        entityService.getByInode(newInode, UserAction.UPLOAD_FILE).let {
            if (it.hasError) return it.cast()
            if (it.isSuccessful && it.value.entityId != pathEntity.entityId) {
                entityService.updateInode(
                    entityId = it.value.entityId,
                    newInode = null,
                    existingEntity = it.value,
                    userAction = UserAction.UPLOAD_FILE,
                ).let { updateResult ->
                    if (updateResult.isNotSuccessful) return updateResult.cast()
                }
            }
        }

        return entityService.updateInode(
            entityId = pathEntity.entityId,
            newInode = newInode,
            existingEntity = pathEntity,
            userAction = UserAction.UPLOAD_FILE,
        )
    }

    private fun moveUploadedFile(user: Principal, uploadLocation: FilePath, target: FilePath): Result<Unit> {
        return filesystemService.moveFile(
            user = user,
            source = uploadLocation,
            destination = target,
            ignorePermissions = true,
        )
    }

    private fun createEntityOrRollback(user: Principal, canonicalPath: FilePath): Result<Unit> {
        indexUploadedPath(user, canonicalPath).let {
            if (it.isSuccessful) return Result.ok(Unit)
            filesystemService.deleteFile(user = user, target = canonicalPath, ignorePermissions = true)
            return Result.error(it.errorOrNull ?: "Failed to index uploaded file.")
        }
    }

    // A moved upload can match an existing detached entity by inode. Reconnect that
    // entity before inserting so SQLite's unique inode constraint is not hit after
    // the file was already written successfully.
    private fun indexUploadedPath(user: Principal, canonicalPath: FilePath): Result<Unit> {
        entityService.getByPath(canonicalPath.pathString, UserAction.UPLOAD_FILE).let {
            if (it.isSuccessful) return Result.ok(Unit)
            if (it.hasError) return it.cast()
        }

        val inode = filesystemService.getInode(canonicalPath.path, followSymbolicLinks = false)
        if (inode != null) {
            entityService.getByInode(inode, UserAction.UPLOAD_FILE).let { inodeEntityResult ->
                if (inodeEntityResult.hasError) return inodeEntityResult.cast()

                if (inodeEntityResult.isSuccessful) {
                    val inodeEntity = inodeEntityResult.value
                    if (inodeEntity.path != canonicalPath.pathString) {
                        entityService.getByPath(canonicalPath.pathString, UserAction.UPLOAD_FILE).let { pathEntityResult ->
                            if (pathEntityResult.hasError) return pathEntityResult.cast()
                            if (pathEntityResult.isSuccessful && pathEntityResult.value.entityId != inodeEntity.entityId) {
                                entityService.updatePath(
                                    entityId = pathEntityResult.value.entityId,
                                    newPath = null,
                                    existingEntity = pathEntityResult.value,
                                    userAction = UserAction.UPLOAD_FILE,
                                ).let {
                                    if (it.isNotSuccessful) return it.cast()
                                }
                            }
                        }

                        entityService.updatePath(
                            entityId = inodeEntity.entityId,
                            newPath = canonicalPath.pathString,
                            existingEntity = inodeEntity,
                            userAction = UserAction.UPLOAD_FILE,
                        ).let {
                            if (it.isNotSuccessful) return it.cast()
                        }
                    }

                    return Result.ok(Unit)
                }
            }
        }

        entityService.create(
            canonicalPath = canonicalPath,
            ownerId = user.userId,
            userAction = UserAction.UPLOAD_FILE,
        ).let {
            if (it.isSuccessful || it.rejected) return Result.ok(Unit)
            return it.cast()
        }
    }

    // Idempotent mkdir: if another session already created this folder, success is
    // fine as long as the path is a directory.
    private fun ensureDirectory(user: Principal, path: FilePath, createdDirectories: MutableList<String>): Result<Unit> {
        if (path.exists(LinkOption.NOFOLLOW_LINKS)) {
            if (!path.path.isDirectory(LinkOption.NOFOLLOW_LINKS)) return Result.reject("A file exists where a folder is needed.")
            return Result.ok(Unit)
        }

        filesystemService.createFolder(path).let {
            if (it.isNotSuccessful) return it.cast()
        }

        entityService.create(
            canonicalPath = path,
            ownerId = user.userId,
            userAction = UserAction.CREATE_FOLDER,
        ).let {
            if (it.isNotSuccessful && !it.rejected) return it.cast()
        }

        createdDirectories += path.pathString
        return Result.ok(Unit)
    }

    // Build the concrete target paths used for permission preflight.
    private fun preflightTargetPaths(destinationParent: FilePath, normalized: NormalizedManifest): List<Pair<String, FilePath>> {
        return normalized.directories.mapNotNull { relativePath ->
            resolveUploadTarget(destinationParent, relativePath)?.let { relativePath to it }
        } + normalized.files.mapNotNull { file ->
            resolveUploadTarget(destinationParent, file.relativePath)?.let { file.relativePath to it }
        }
    }

    // Check the nearest existing ancestor for each target. This catches nested
    // permission boundaries without repeating the same folder check for every child file.
    private fun permissionBlocks(user: Principal, destinationParent: FilePath, normalized: NormalizedManifest): List<FolderUploadBlockedPath> {
        val checked = mutableSetOf<FilePath>()
        val blocked = mutableListOf<FolderUploadBlockedPath>()

        preflightTargetPaths(destinationParent, normalized).forEach { (relativePath, target) ->
            val permissionPath = nearestExistingPath(target)
            if (!checked.add(permissionPath)) return@forEach

            fileService.isAllowedToEditFile(user, permissionPath).let {
                if (it.isNotSuccessful) {
                    blocked += blocked(relativePath, target, it.errorOrNull ?: "Missing permission to upload here.")
                }
            }
        }

        return blocked
    }

    // Resolve and authorize the selected destination folder before manifest paths
    // are joined under it.
    private fun resolveDestinationParent(user: Principal, destinationPath: String): Result<FilePath> {
        val destinationParent = resolvePath(FilePath.of(destinationPath)).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }

        fileService.isAllowedToEditFile(user, destinationParent).let {
            if (it.isNotSuccessful) return it.cast()
        }

        return Result.ok(destinationParent)
    }

    private fun getSession(sessionId: String): Result<FolderUploadSession> {
        cleanupExpiredSessions()
        val session = sessions[sessionId] ?: return Result.notFound()
        if (session.expiresAt <= unixNowMillis()) {
            sessions.remove(sessionId)
            return Result.notFound()
        }
        return Result.ok(session)
    }

    private fun cleanupExpiredSessions() {
        val now = unixNowMillis()
        sessions.entries.removeIf { it.value.expiresAt <= now }
    }

    // Normalize browser-provided relative paths into a safe logical tree. These are
    // not real filesystem paths until resolveUploadTarget joins them to destinationParent.
    private fun normalizeManifest(manifest: FolderUploadManifest): Result<NormalizedManifest> {
        if (manifest.rootName.isBlank()) return Result.reject("Folder name is missing.")

        val normalizedRoot = validateRelativePath(manifest.rootName).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }
        if (normalizedRoot.contains("/")) return Result.reject("Folder name is invalid.")

        val directoryPaths = linkedSetOf(normalizedRoot)
        manifest.directories.forEach { directory ->
            val normalized = validateRelativePath(directory.relativePath).let {
                if (it.isNotSuccessful) return it.cast()
                it.value
            }
            if (!isUnderRoot(normalized, normalizedRoot)) return Result.reject("Directory path is outside the uploaded folder.")
            directoryPaths += normalized
        }

        val files = manifest.files.map { file ->
            if (file.size < 0) return Result.reject("File size is invalid.")
            val normalized = validateRelativePath(file.relativePath).let {
                if (it.isNotSuccessful) return it.cast()
                it.value
            }
            if (!isUnderRoot(normalized, normalizedRoot)) return Result.reject("File path is outside the uploaded folder.")

            val parent = normalized.substringBeforeLast("/", "")
            if (parent.isNotBlank()) directoryPaths += parent
            NormalizedFile(relativePath = normalized, size = file.size, lastModified = file.lastModified)
        }

        val duplicateFile = files.groupBy { duplicateKey(it.relativePath) }.entries.firstOrNull { it.value.size > 1 }
        if (duplicateFile != null) return Result.reject("Duplicate file path in upload manifest: ${duplicateFile.value.first().relativePath}")

        val filePaths = files.map { it.relativePath }.toSet()
        filePaths.firstOrNull { directoryPaths.contains(it) }?.let {
            return Result.reject("Path is listed as both a file and a folder: $it")
        }

        filePaths.forEach { filePath ->
            directoryPaths.firstOrNull { directoryPath -> directoryPath.startsWith(addSlash(filePath)) }?.let {
                return Result.reject("A file path is used as a folder: $filePath")
            }
        }

        val totalBytes = files.sumOf { it.size }
        val totalPathBytes = directoryPaths.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } +
            files.sumOf { it.relativePath.toByteArray(Charsets.UTF_8).size.toLong() }

        return Result.ok(
            NormalizedManifest(
                destinationPath = manifest.destinationPath,
                rootName = normalizedRoot,
                directories = directoryPaths.toList(),
                files = files,
                summary = FolderUploadSummary(
                    fileCount = files.size,
                    directoryCount = directoryPaths.size,
                    totalBytes = totalBytes,
                    totalPathBytes = totalPathBytes,
                ),
            )
        )
    }

    private fun validateLimits(manifest: NormalizedManifest): Result<Unit> {
        if (manifest.files.size > maxFiles) return Result.reject("Too many files selected. Maximum is $maxFiles.")
        if (manifest.directories.size > maxDirectories) return Result.reject("Too many folders selected. Maximum is $maxDirectories.")
        if (manifest.summary.totalBytes > maxTotalBytes) return Result.reject("Selected folder is too large.")
        if (manifest.summary.totalPathBytes > maxTotalPathBytes) return Result.reject("Selected folder has too many path characters.")
        return Result.ok(Unit)
    }

    // Reject path traversal, absolute paths, empty segments, and platform-specific
    // invalid names before any server path is constructed.
    private fun validateRelativePath(input: String): Result<String> {
        val normalized = input.replace('\\', '/').trim('/')
        if (normalized.isBlank()) return Result.reject("Path is empty.")
        if (input.startsWith("/") || input.startsWith("\\")) return Result.reject("Absolute paths are not allowed.")
        if (normalized.contains("//")) return Result.reject("Empty path segments are not allowed.")
        if (normalized.any { it.code < 32 || it == 0.toChar() }) return Result.reject("Path contains invalid characters.")

        val segments = normalized.split("/")
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return Result.reject("Path contains invalid segments.")
        segments.forEach { segment ->
            validateSegmentForPlatform(segment).let {
                if (it.isNotSuccessful) return it.cast()
            }
        }

        return Result.ok(normalized)
    }

    private fun validateSegmentForPlatform(segment: String): Result<Unit> {
        if (segment.contains('/')) return Result.reject("Path segment contains a separator.")
        if (platform == FolderUploadPlatform.WINDOWS) {
            if (segment.endsWith(" ") || segment.endsWith(".")) return Result.reject("Windows paths cannot end with a space or dot.")
        }
        return Result.ok(Unit)
    }

    private fun duplicateKey(path: String): String {
        return when (platform) {
            FolderUploadPlatform.LINUX -> path
            FolderUploadPlatform.WINDOWS -> path.lowercase()
        }
    }

    private fun isUnderRoot(path: String, root: String): Boolean {
        return path == root || path.startsWith(addSlash(root))
    }

    private fun existingType(path: FilePath): FolderUploadEntryType? {
        if (!path.exists(LinkOption.NOFOLLOW_LINKS)) return null
        return when {
            path.path.isDirectory(LinkOption.NOFOLLOW_LINKS) -> FolderUploadEntryType.DIRECTORY
            path.path.isRegularFile(LinkOption.NOFOLLOW_LINKS) -> FolderUploadEntryType.FILE
            else -> FolderUploadEntryType.OTHER
        }
    }

    // Non-existent upload targets inherit permissions from their closest existing
    // ancestor, because only existing paths can be resolved and checked.
    private fun nearestExistingPath(path: FilePath): FilePath {
        var current: Path? = if (path.exists(LinkOption.NOFOLLOW_LINKS)) path.path else path.path.parent
        while (current != null && !current.exists(LinkOption.NOFOLLOW_LINKS)) {
            current = current.parent
        }
        return FilePath.ofAlreadyNormalized(current ?: path.path.root ?: path.path)
    }

    // Shared keep-both naming: name.ext, name (1).ext, name (2).ext, ...
    private fun resolveKeepBothPath(path: FilePath): FilePath {
        val parent = path.path.parent
        val filename = path.path.fileName.toString()
        val splitName = filename.splitByLast(".")
        val baseName = splitName.first
        val extension = splitName.second?.let { ".$it" } ?: ""

        var counter = 0
        var candidatePath: Path
        do {
            val suffix = if (counter == 0) "" else " ($counter)"
            candidatePath = parent.resolve("$baseName$suffix$extension")
            counter++
        } while (candidatePath.exists(LinkOption.NOFOLLOW_LINKS))

        return FilePath.ofAlreadyNormalized(candidatePath)
    }

    // Applies a directory conflict's keep-both remap to all descendants in that branch.
    private fun applyDirectoryRemaps(relativePath: String, destinationParent: FilePath, remaps: List<DirectoryRemap>): FilePath {
        val remap = remaps
            .filter { relativePath == it.relativePath || relativePath.startsWith(addSlash(it.relativePath)) }
            .maxByOrNull { it.relativePath.length }
            ?: return resolveUploadTarget(destinationParent, relativePath) ?: destinationParent

        val suffix = relativePath.removePrefix(remap.relativePath).trimStart('/')
        return if (suffix.isBlank()) {
            remap.targetPath
        } else {
            FilePath.ofAlreadyNormalized(remap.targetPath.path.resolve(suffix).normalize())
        }
    }

    private fun blocked(relativePath: String, targetPath: FilePath?, message: String): FolderUploadBlockedPath {
        return FolderUploadBlockedPath(
            relativePath = relativePath,
            targetPath = targetPath?.pathString ?: relativePath,
            message = message,
        )
    }

    // Converts a safe browser-relative path into a concrete Filemat path under the
    // selected destination. Returning null means the path escaped the destination.
    private fun resolveUploadTarget(destinationParent: FilePath, relativePath: String): FilePath? {
        val target = destinationParent.path.resolve(relativePath).normalize()
        if (!target.startsWith(destinationParent.path)) return null
        return FilePath.ofAlreadyNormalized(target)
    }

    private fun addSlash(path: String) = "$path/"
    private fun unixNowMillis() = Instant.now().toEpochMilli()

    private data class NormalizedManifest(
        val destinationPath: String,
        val rootName: String,
        val directories: List<String>,
        val files: List<NormalizedFile>,
        val summary: FolderUploadSummary,
    )

    private data class NormalizedFile(
        val relativePath: String,
        val size: Long,
        val lastModified: Long,
    )

    private data class DirectoryRemap(
        val relativePath: String,
        val targetPath: FilePath,
    )

    private data class FolderUploadSession(
        val sessionId: String,
        val userId: Ulid,
        val destinationParent: FilePath,
        val files: Map<String, FolderUploadSessionFilePlan>,
        val createdAt: Long,
        val expiresAt: Long,
    )

    private data class FolderUploadSessionFilePlan(
        val relativePath: String,
        val targetPath: FilePath,
        val resolution: FolderUploadResolution,
    )

    companion object {
        const val CONFLICT_PREFIX = "__CONFLICT__"
    }
}
