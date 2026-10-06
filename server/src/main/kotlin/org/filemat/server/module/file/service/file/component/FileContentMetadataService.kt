package org.filemat.server.module.file.service.file.component

import com.github.benmanes.caffeine.cache.Caffeine
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_QUIET
import org.bytedeco.ffmpeg.global.avutil.av_log_set_level
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.util.md5hash
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FileContentMetadata
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.LockType
import org.filemat.server.module.file.service.file.FileService
import org.springframework.stereotype.Service
import java.io.File
import javax.imageio.ImageIO

@Service
class FileContentMetadataService(
    private val fileService: FileService,
    private val fileLockService: FileLockService,
) {

    // Width, height, and duration. Key is path hash, modified time, and size.
    private val cache = Caffeine.newBuilder()
        .maximumSize(10_000)
        .build<String, FileContentMetadata>()

    fun getContentMetadata(
        user: Principal?,
        rawPath: FilePath,
        shareToken: String?,
    ): Result<FileContentMetadata> {
        val canonicalPath = fileService.resolvePathWithOptionalShare(rawPath, shareToken).let {
            if (it.isNotSuccessful) return it.cast()
            it.value
        }

        fileService.isAllowedToAccessFile(
            user = user,
            canonicalPath = canonicalPath,
            ignorePermissions = shareToken != null,
        ).let {
            if (it.isNotSuccessful) return it.cast()
        }

        val lock = fileLockService.getLock(canonicalPath.path, LockType.READ)
        if (!lock.successful) return Result.reject("This file is currently being modified.")

        try {
            val file = canonicalPath.path.toFile()
            if (!file.isFile || !file.canRead()) return Result.notFound()
            val filename = rawPath.pathString.substringAfterLast("/")
            return Result.ok(getCachedOrExtract(file, filename, canonicalPath))
        } finally {
            lock.unlock()
        }
    }

    fun getContentMetadataBatch(
        user: Principal?,
        rawPaths: List<FilePath>,
        shareToken: String?,
    ): Map<String, FileContentMetadata> {
        val result = linkedMapOf<String, FileContentMetadata>()
        for (rawPath in rawPaths) {
            val meta = getContentMetadata(user, rawPath, shareToken)
            if (!meta.isSuccessful) continue
            result[rawPath.pathString] = meta.value
        }
        return result
    }

    /** Store metadata already read while making a thumbnail, so a later fetch does not decode the file again. */
    fun putFromProbe(
        canonicalPath: FilePath,
        modifiedDate: Long,
        fileSize: Long,
        meta: FileContentMetadata,
    ) {
        if (!meta.hasAnyField()) return
        cache.put(cacheKey(canonicalPath, modifiedDate, fileSize), meta)
    }

    internal fun getCachedOrExtract(file: File, filename: String, canonicalPath: FilePath): FileContentMetadata {
        val key = cacheKey(canonicalPath, file.lastModified(), file.length())
        return cache.get(key) { extractFromFile(file, filename) }
    }

    internal fun extractFromFile(file: File, filename: String): FileContentMetadata {
        return when (mediaKindFromFilename(filename)) {
            MediaKind.IMAGE -> {
                readImageSize(file)?.toMetadata()
                    // Stills have no duration; drop whatever ffmpeg reports.
                    ?: readWithFfmpeg(file)?.copy(durationMs = null)
                    ?: FileContentMetadata()
            }
            MediaKind.VIDEO -> readWithFfmpeg(file) ?: FileContentMetadata()
            // Audio has no frame size.
            MediaKind.AUDIO -> readWithFfmpeg(file)?.copy(width = null, height = null) ?: FileContentMetadata()
            null -> {
                readImageSize(file)?.toMetadata()
                    ?: readWithFfmpeg(file)
                    ?: FileContentMetadata()
            }
        }
    }

    private fun readImageSize(file: File): Pair<Int, Int>? {
        return try {
            val stream = ImageIO.createImageInputStream(file) ?: return null
            stream.use {
                val readers = ImageIO.getImageReaders(it)
                if (!readers.hasNext()) return null
                val reader = readers.next()
                try {
                    reader.input = it
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (width > 0 && height > 0) width to height else null
                } finally {
                    reader.dispose()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readWithFfmpeg(file: File): FileContentMetadata? {
        var grabber: FFmpegFrameGrabber? = null
        return try {
            av_log_set_level(AV_LOG_QUIET)
            grabber = FFmpegFrameGrabber(file)
            grabber.start()
            metadataFromGrabber(grabber, includeDuration = true)
        } catch (_: Throwable) {
            null
        } finally {
            grabber?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
        }
    }

    private enum class MediaKind { IMAGE, VIDEO, AUDIO }

    companion object {
        private val imageExtensions = setOf(
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "ico", "jfif", "wbmp", "avif", "heic",
        )
        private val videoExtensions = setOf("mp4", "webm", "ogg", "ogv", "mov", "avi")
        private val audioExtensions = setOf("mp3", "wav", "m4a", "aac")

        fun cacheKey(canonicalPath: FilePath, modifiedDate: Long, fileSize: Long): String {
            return "${md5hash(canonicalPath.pathString)}_${modifiedDate}_${fileSize}"
        }

        fun metadataFromGrabber(grabber: FFmpegFrameGrabber, includeDuration: Boolean): FileContentMetadata? {
            val width = grabber.imageWidth.takeIf { it > 0 }
            val height = grabber.imageHeight.takeIf { it > 0 }
            // FFmpeg lengthInTime is microseconds.
            val durationMs = if (includeDuration) grabber.lengthInTime.takeIf { it > 0 }?.div(1000) else null
            if (width == null && height == null && durationMs == null) return null
            return FileContentMetadata(width = width, height = height, durationMs = durationMs)
        }

        private fun mediaKindFromFilename(filename: String): MediaKind? {
            val extension = filename.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            if (extension.isEmpty() || extension == filename.lowercase()) return null
            return when (extension) {
                in imageExtensions -> MediaKind.IMAGE
                in videoExtensions -> MediaKind.VIDEO
                in audioExtensions -> MediaKind.AUDIO
                else -> null
            }
        }

        private fun Pair<Int, Int>.toMetadata() = FileContentMetadata(width = first, height = second)
    }
}
