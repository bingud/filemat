package org.filemat.server.module.file.service.file.component

import com.github.benmanes.caffeine.cache.Caffeine
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.util.md5hash
import org.filemat.server.config.platform.LinkInspector
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FileContentMetadata
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.FileLockService
import org.filemat.server.module.file.service.LockType
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.file.media.FfmpegBusyException
import org.filemat.server.module.file.service.file.media.MediaPreviewService
import org.springframework.stereotype.Service
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStreamImpl

@Service
class FileContentMetadataService(
    private val fileService: FileService,
    private val fileLockService: FileLockService,
    private val mediaPreviewService: MediaPreviewService,
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
            // Reading a OneDrive online-only file downloads it, so it has no media metadata, like it has no preview (Q42)
            if (LinkInspector.kindOf(canonicalPath.path) == LinkInspector.Kind.DATA) return Result.ok(FileContentMetadata())
            val filename = rawPath.filename
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
            // The rest are left out, and the web asks for them again later
            val meta = try {
                getContentMetadata(user, rawPath, shareToken)
            } catch (_: FfmpegBusyException) {
                break
            }
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

    /**
     * Throws [FfmpegBusyException], which caches nothing, when FFmpeg is needed and busy
     */
    internal fun getCachedOrExtract(file: File, filename: String, canonicalPath: FilePath): FileContentMetadata {
        val key = cacheKey(canonicalPath, file.lastModified(), file.length())
        return cache.get(key) { extractFromFile(file, filename) }
    }

    internal fun extractFromFile(file: File, filename: String): FileContentMetadata {
        return when (mediaKindFromFilename(filename)) {
            MediaKind.IMAGE -> {
                readImageSize(file.toPath())?.toMetadata()
                    // Stills have no duration; drop whatever ffmpeg reports.
                    ?: readWithFfmpeg(file)?.copy(durationMs = null)
                    ?: FileContentMetadata()
            }
            MediaKind.VIDEO -> readWithFfmpeg(file) ?: FileContentMetadata()
            // Audio has no frame size.
            MediaKind.AUDIO -> readWithFfmpeg(file)?.copy(width = null, height = null) ?: FileContentMetadata()
            null -> {
                readImageSize(file.toPath())?.toMetadata()
                    ?: readWithFfmpeg(file)
                    ?: FileContentMetadata()
            }
        }
    }

    private fun readWithFfmpeg(file: File): FileContentMetadata? {
        return mediaPreviewService.backend?.probe(file.toPath())
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

/**
 * Reads the width and height from the image header, without decoding the pixels. Null when ImageIO cannot read the format.
 */
internal fun readImageSize(path: Path): Pair<Int, Int>? {
    return try {
        // NIO opens files with share-delete, so a file being read can still be renamed or deleted on Windows (§6.1).
        // The stream seeks in the file: a header at the end of a large TIFF is read there, not copied into memory first.
        Files.newByteChannel(path).use { channel ->
            ChannelImageInputStream(channel).use {
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
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * An `ImageInputStream` that reads a file through a NIO channel, with random access.
 * Only the last 8 KB read are kept, because readers read headers a byte at a time.
 */
private class ChannelImageInputStream(private val channel: SeekableByteChannel) : ImageInputStreamImpl() {
    private val buffer = ByteArray(8192)
    private var bufferStart = 0L
    private var bufferLength = 0

    override fun read(): Int {
        checkClosed()
        bitOffset = 0
        if (!fill()) return -1
        return buffer[(streamPos++ - bufferStart).toInt()].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        checkClosed()
        bitOffset = 0
        if (len == 0) return 0
        if (!fill()) return -1
        val count = minOf(len, (bufferStart + bufferLength - streamPos).toInt())
        System.arraycopy(buffer, (streamPos - bufferStart).toInt(), b, off, count)
        streamPos += count
        return count
    }

    /**
     * Makes the buffer hold the byte at the stream position. Returns false at the end of the file.
     */
    private fun fill(): Boolean {
        if (streamPos >= bufferStart && streamPos < bufferStart + bufferLength) return true
        channel.position(streamPos)
        val count = channel.read(ByteBuffer.wrap(buffer))
        if (count <= 0) return false
        bufferStart = streamPos
        bufferLength = count
        return true
    }

    override fun length(): Long = runCatching { channel.size() }.getOrDefault(-1L)

    override fun close() {
        super.close()
        channel.close()
    }
}
