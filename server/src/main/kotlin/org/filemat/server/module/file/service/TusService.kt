package org.filemat.server.module.file.service

import  jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.*
import me.desair.tus.server.TusFileUploadService
import me.desair.tus.server.upload.UploadInfo
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.util.*
import org.filemat.server.common.util.classes.wrappers.BufferedResponseWrapper
import org.filemat.server.common.util.classes.wrappers.RequestPathOverrideWrapper
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FolderUploadResolution
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.model.UserAction
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.nio.file.NoSuchFileException
import java.time.Duration
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock
import kotlin.properties.Delegates


/**
 * Service for file uploading with TUS service
 */
@Service
class TusService(
    @Lazy private val filesystem: FilesystemService,
    private val fileService: FileService,
    private val logService: LogService,
    @Lazy private val folderUploadService: FolderUploadService,
) {
    val uploadLock = ReentrantReadWriteLock()
    private final val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    final var tusFileService: TusFileUploadService by Delegates.notNull()
        private set

    fun initializeTusService(): Boolean {
        try {
            tusFileService = TusFileUploadService()
                .withUploadUri("/api/v1/file/upload")
                .withStoragePath(State.App.uploadFolderPath)
                .withUploadExpirationPeriod(Duration.ofHours(48).toMillis())

            startTusCleanupLoop()
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private var isCleanupRunning = false
    fun startTusCleanupLoop() {
        if (isCleanupRunning) return
        isCleanupRunning = true

        scope.launch {
            var logged = false

            while (true) {
                try {
                    uploadLock.readLock().withLock {
                        tusFileService.cleanup()
                    }
                } catch (e: NoSuchFileException) {
                    // Ignore error if upload file not found
                } catch (e: Exception) {
                    if (!logged) {
                        logged = true
                        logService.error(
                            type = LogType.SYSTEM,
                            action = UserAction.NONE,
                            description = "Failed to clear expired TUS upload files",
                            message = e.stackTraceToString(),
                        )
                    }
                }

                delay(Duration.ofHours(2).toMillis())
            }
        }
    }

    /**
     * Handles a file upload request with TUS
     */
    fun handleTusUpload(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        uploadLock.readLock().withLock {
            response.contentType = "application/json"
            response.characterEncoding = "UTF-8"

            // Get the TUS service instance
            val user = request.getPrincipal()!!

            // Handle a POST request
            if (request.method == "POST") {
                handlePostRequest(
                    request = request,
                    response = response,
                    user = user,
                ).let {
                    if (it == false) return
                }
            }

            // Wrap request to change the path, so that TUS receives the api prefix
            val wrappedRequest = RequestPathOverrideWrapper(request, "/api${request.requestURI}")
            // Wrap response so that TUS cannot breach containment and send response too soon
            val wrappedResponse = BufferedResponseWrapper(response)
            // Make TUS handle uploads
            tusFileService.process(wrappedRequest, wrappedResponse)

            // Handle a PATCH request
            if (request.method == "PATCH") {
                handlePatchRequest(
                    request = request,
                    wrappedRequest = wrappedRequest,
                    response = response,
                    tusService = tusFileService,
                    user = user
                ).let {
                    if (it.isNotSuccessful) return
                    val actualFilename = it.valueOrNull
                    if (actualFilename != null) {
                        val actualFilenameEncoded = encodeToBase64(actualFilename)
                        wrappedResponse.setHeader("actual-uploaded-filename", actualFilenameEncoded)
                    }
                }
            }

            // Send TUS response
            wrappedResponse.copyTo(response)
        }
    }

    /**
     * Handles a TUS `POST` request
     */
    private fun handlePostRequest(
        request: HttpServletRequest,
        response: HttpServletResponse,
        user: Principal,
    ): Boolean {
        response.addHeader("Tus-Resumable", "1.0.0")

        // Get metadata to get destination filename
        val rawMeta: String? = request.getHeader("Upload-Metadata")
        if (rawMeta == null) {
            response.respond(400, "Invalid upload metadata")
            return false
        }
        val meta = parseTusHttpHeader(rawMeta)

        val folderUploadSessionId = meta["folderUploadSessionId"]
        val relativePath = meta["relativePath"]
        if (folderUploadSessionId != null || relativePath != null) {
            if (folderUploadSessionId == null || relativePath == null) {
                response.respond(400, "Invalid folder upload metadata")
                return false
            }

            folderUploadService.validateTusUploadStart(
                user = user,
                sessionId = folderUploadSessionId,
                relativePath = relativePath,
                uploadLength = request.getHeader("Upload-Length")?.toLongOrNull(),
            ).let {
                if (it.notFound) {
                    response.respond(400, "Folder upload session expired.")
                    return false
                }
                if (it.isNotSuccessful) {
                    response.respond(400, it.errorOrNull ?: "Invalid folder upload session.")
                    return false
                }
            }

            return true
        }

        // Get user inputted upload destination
        val rawPath = meta["path"]?.toFilePath()
        if (rawPath == null) {
            response.respond(400, "Invalid path")
            return false
        }

        // Resolve the upload destination path
        val destinationParentPath = let {
            val parent = rawPath.path.parent.toString()

            resolvePath(FilePath.of(parent)).let { result ->
                if (result.notFound) {
                    response.respond(400, "The target folder does not exist.")
                    return false
                } else if (result.isNotSuccessful) {
                    response.respond(500, "Failed to save the uploaded file.")
                    return false
                }
                result.value
            }
        }

        // Authenticate destination path
        val isAllowed = fileService.isAllowedToEditFile(user = user, canonicalPath = destinationParentPath)
        if (isAllowed.isNotSuccessful) {
            response.respond(400, isAllowed.errorOrNull ?: "You do not have permission to access this folder.")
            return false
        }

        return true
    }


    /**
     * Handles a TUS `PATCH` request
     *
     * @return null if successful, path of uploaded file if upload was finished
     */
    private fun handlePatchRequest(
        request: HttpServletRequest,
        wrappedRequest: HttpServletRequest,
        response: HttpServletResponse,
        tusService: TusFileUploadService,
        user: Principal
    ): Result<String?> {
        val info: UploadInfo? = tusService.getUploadInfo(wrappedRequest.requestURI)
        if (info != null && !info.isUploadInProgress) {
            val isUploaded = info.length == info.offset

            if (isUploaded) {
                // Move the file from the uploads folder to the target destination
                val result = handleUploadedFile(user, info)
                if (result.isNotSuccessful) {
                    if (result.notFound) {
                        response.respond(400, "Target folder does not exist.")
                    } else if (result.errorOrNull?.startsWith(FolderUploadService.CONFLICT_PREFIX) == true) {
                        response.respond(409, result.error.removePrefix(FolderUploadService.CONFLICT_PREFIX), "conflict")
                    } else if (result.hasError) {
                        response.respond(500, result.error)
                    } else {
                        response.respond(400, result.error)
                    }
                }

                return result.cast()
            }
        }

        return Result.ok(null)
    }

    /**
     * Handles when a file was uploaded
     *
     * Moves the file and clears the upload directory
     *
     * @return the file path where the file was uploaded
     */
    private fun handleUploadedFile(user: Principal, info: UploadInfo): Result<String?> {
        // Get the current uploaded file location
        val sourceFolder = "${State.App.uploadFolderPath}/uploads/${info.id}"
        val uploadLocation = "$sourceFolder/data".toFilePath()

        val folderUploadSessionId = info.metadata["folderUploadSessionId"]
        val relativePath = info.metadata["relativePath"]
        if (folderUploadSessionId != null || relativePath != null) {
            if (folderUploadSessionId == null || relativePath == null) {
                // Prefer path-based resume finalize when folder metadata is incomplete but path exists.
                if (info.metadata["path"] == null) {
                    return Result.reject("Invalid folder upload metadata.")
                }
            } else {
                val folderResult = folderUploadService.finalizeTusUpload(
                    user = user,
                    sessionId = folderUploadSessionId,
                    relativePath = relativePath,
                    uploadLocation = uploadLocation,
                )
                if (folderResult.isSuccessful) {
                    filesystem.deleteFile(user = user, target = sourceFolder.toFilePath(), ignorePermissions = true)
                    return Result.ok(folderResult.value.actualFilename)
                }
                // Session gone after tab refresh — fall through to path + resolution metadata.
                if (!folderResult.notFound) {
                    return folderResult.cast()
                }
            }
        }

        return finalizeByPathMetadata(user, info, uploadLocation, sourceFolder)
    }

    /**
     * Session-less finalize using TUS metadata.path and optional metadata.resolution.
     */
    private fun finalizeByPathMetadata(
        user: Principal,
        info: UploadInfo,
        uploadLocation: FilePath,
        sourceFolder: String,
    ): Result<String?> {
        val rawDestinationPath = info.metadata["path"]?.toFilePath()
            ?: return Result.error("Destination path is not in upload metadata.")

        val resolution = when (info.metadata["resolution"]?.lowercase()) {
            "overwrite" -> FolderUploadResolution.OVERWRITE
            "keep-both" -> FolderUploadResolution.KEEP_BOTH
            "skip" -> FolderUploadResolution.SKIP
            else -> null
        }

        // Resolve destination so symlinks / canonical parents match the rest of the app.
        val rawDestinationParent = getParentFromPath(rawDestinationPath)
        val filename = getFilenameFromPath(rawDestinationPath.path)
        val destinationParent = resolvePath(rawDestinationParent).let { result ->
            if (result.isNotSuccessful) return result.cast()
            result.value
        }
        val targetPath = if (destinationParent == rawDestinationParent) {
            rawDestinationPath
        } else {
            FilePath.ofAlreadyNormalized(destinationParent.path.resolve(filename))
        }

        val finalized = folderUploadService.finalizeResumedUpload(
            user = user,
            uploadLocation = uploadLocation,
            targetPath = targetPath,
            resolution = resolution,
        ).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }

        filesystem.deleteFile(user = user, target = sourceFolder.toFilePath(), ignorePermissions = true)
        return Result.ok(finalized.actualFilename)
    }

    private fun HttpServletResponse.respond(code: Int, message: String, error: String = "custom") {
        this.status = code
        val json = """ {"message":"$message","error":"$error"} """
        this.writer.write(json)
    }
}