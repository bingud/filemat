package org.filemat.server.module.file.service

import  jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
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
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.exists


/**
 * Service for file uploading with TUS service
 */
@Service
class TusService(
    private val filesystem: FilesystemService,
    private val fileService: FileService,
    private val entityService: EntityService
) {

    /**
     * Handles a file upload request with TUS
     */
    fun handleTusUpload(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"

        // Get the TUS service instance
        val tusService = filesystem.tusFileService
        if (tusService == null) return response.respond(500, "File upload service is not running.")

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
        tusService.process(wrappedRequest, wrappedResponse)

        // Handle a PATCH request
        if (request.method == "PATCH") {
            handlePatchRequest(
                request = request,
                wrappedRequest = wrappedRequest,
                response = response,
                tusService = tusService,
                user = user
            ).let {
                if (it.isNotSuccessful) return
                val actualFilename = it.valueOrNull
                if (actualFilename != null) {
                    wrappedResponse.setHeader("actual-uploaded-filename", actualFilename)
                }
            }
        }

        // Send TUS response
       wrappedResponse.copyTo(response)
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

        // Get user inputted upload destination
        val rawFilename = meta["filename"]?.toFilePath()
        if (rawFilename == null) {
            response.respond(400, "Invalid filename")
            return false
        }

        // Resolve the upload destination path
        val destinationParentPath = let {
            val parent = rawFilename.path.parent.toString()

            resolvePath(FilePath.of(parent)).let { (result, hasSymlink) ->
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
        val isAllowed = fileService.isAllowedToAccessFile(user = user, canonicalPath = destinationParentPath)
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
    private fun handleUploadedFile(user: Principal, info: UploadInfo): Result<String> {
        // Get the current uploaded file location
        val sourceFolder = "${State.App.uploadFolderPath}/uploads/${info.id}"
        val uploadLocation = "$sourceFolder/data".toFilePath()

        // Get destination paths
        val rawDestinationPath = info.metadata["filename"]?.toFilePath() ?: return Result.error("Destination filename is not in upload metadata.")
        val rawDestinationParent = getParentFromPath(rawDestinationPath)
        val filename = getFilenameFromPath(rawDestinationPath.path)

        // Resolve the destination parent folder
        val destinationParent = let {
            resolvePath(rawDestinationParent).let { (result, hadSymlink) ->
                if (result.isNotSuccessful) return result.cast();
                result.value
            }
        }

        // Get the resolved destination path
        val uncheckedDestinationPath = if (destinationParent == rawDestinationParent) {
            rawDestinationPath.path
        } else {
            destinationParent.path.resolve(filename)
        }

        // Add a number to the filename if it already exists
        val (destinationPath, actualFilename) = let {
            // Extract base name and extension
            val splitName = filename.splitByLast(".")
            val baseName = splitName.first
            val extension = splitName.second?.let { ".$it" } ?: ""

            // Try “name.ext”, “name (1).ext”, “name (2).ext”, …
            var counter = 0
            var candidatePath: Path
            var candidateName = ""
            do {
                val suffix = if (counter == 0) "" else " ($counter)"
                candidateName = "$baseName$suffix$extension"
                candidatePath = uncheckedDestinationPath.parent.resolve(candidateName)
                counter++
            } while (candidatePath.exists(LinkOption.NOFOLLOW_LINKS))

            FilePath.ofAlreadyNormalized(candidatePath) to candidateName
        }

        fileService.isAllowedToAccessFile(user, destinationPath).let {
            if (it.isNotSuccessful) return it.cast()
        }

        // Move the file to the target folder
        val fileMoved = filesystem.moveFile(source = uploadLocation, destination = destinationPath, overwriteDestination = false)
        if (fileMoved.isNotSuccessful) return Result.error("Failed to move the file from the uploads folder.")

        // Delete the TUS upload folder
        filesystem.deleteFile(sourceFolder.toFilePath(), recursive = true)

        // Create an entity
        entityService.create(
            canonicalPath = destinationParent,
            ownerId = user.userId,
            userAction = UserAction.UPLOAD_FILE,
            followSymLinks = State.App.followSymLinks
        )

        return Result.ok(actualFilename)
    }

    private fun HttpServletResponse.respond(code: Int, message: String) {
        this.status = code
        val json = """ {"message":"$message","error":"custom"} """
        this.writer.write(json)
    }
}