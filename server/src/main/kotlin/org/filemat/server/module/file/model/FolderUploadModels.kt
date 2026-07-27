package org.filemat.server.module.file.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FolderUploadManifest(
    val destinationPath: String,
    val rootName: String,
    val directories: List<FolderUploadDirectoryManifest> = emptyList(),
    val files: List<FolderUploadFileManifest> = emptyList(),
)

@Serializable
data class FolderUploadDirectoryManifest(
    val relativePath: String,
)

@Serializable
data class FolderUploadFileManifest(
    val relativePath: String,
    val size: Long,
    val lastModified: Long,
)

@Serializable
data class FolderUploadSessionRequest(
    val manifest: FolderUploadManifest,
    val resolutions: List<FolderUploadResolutionChoice> = emptyList(),
    val defaultResolution: FolderUploadResolution? = null,
)

@Serializable
data class FolderUploadResolutionChoice(
    val relativePath: String,
    val resolution: FolderUploadResolution,
)

@Serializable
enum class FolderUploadResolution {
    @SerialName("overwrite")
    OVERWRITE,

    @SerialName("skip")
    SKIP,

    @SerialName("keep-both")
    KEEP_BOTH,
}

@Serializable
enum class FolderUploadEntryType {
    @SerialName("file")
    FILE,

    @SerialName("directory")
    DIRECTORY,

    @SerialName("other")
    OTHER,
}

@Serializable
data class FolderUploadConflict(
    val relativePath: String,
    val targetPath: String,
    val incomingType: FolderUploadEntryType,
    val existingType: FolderUploadEntryType,
    val allowedResolutions: List<FolderUploadResolution>,
    val message: String? = null,
)

@Serializable
data class FolderUploadBlockedPath(
    val relativePath: String,
    val targetPath: String,
    val message: String,
)

@Serializable
data class FolderUploadSummary(
    val fileCount: Int,
    val directoryCount: Int,
    val totalBytes: Long,
    val totalPathBytes: Long,
)

@Serializable
data class FolderUploadPreflightResponse(
    val conflicts: List<FolderUploadConflict>,
    val blocked: List<FolderUploadBlockedPath>,
    val summary: FolderUploadSummary,
)

@Serializable
data class FolderUploadSessionResponse(
    val sessionId: String? = null,
    val queuedFiles: List<FolderUploadSessionFile> = emptyList(),
    val skippedFiles: List<String> = emptyList(),
    val createdDirectories: List<String> = emptyList(),
    val expiresAt: Long = 0,
    val unresolvedConflicts: List<FolderUploadConflict> = emptyList(),
)

@Serializable
data class FolderUploadSessionFile(
    val relativePath: String,
    val targetPath: String,
    val resolution: FolderUploadResolution,
)
