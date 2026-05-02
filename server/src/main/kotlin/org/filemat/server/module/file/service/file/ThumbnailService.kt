package org.filemat.server.module.file.service.file

import net.coobird.thumbnailator.Thumbnails
import net.coobird.thumbnailator.resizers.configurations.ScalingMode
import org.apache.commons.io.output.TeeOutputStream
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_QUIET
import org.bytedeco.ffmpeg.global.avutil.av_log_set_level
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import org.filemat.server.common.State
import org.filemat.server.common.util.RateLimitedLog
import org.filemat.server.common.util.md5hash
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.FileVisibilityService
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import javax.imageio.ImageIO
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.*

@Service
class ThumbnailService(
    private val fileVisibilityService: FileVisibilityService,
    private val logService: LogService,
) {

    // --- Thumbnail cache timing (FFmpeg / decoder I/O timeouts are not configured here.) ---

    /**
     * Minimum gap between touching a cache file's `lastModified` on repeated cache hits.
     * Milliseconds are fixed once when this service is constructed.
     */
    private val cacheTouchIntervalMs: Long =
        Duration.ofSeconds(10).toMillis().coerceAtLeast(0L)

    /**
     * When nothing forces an earlier pass, wait this long before the next full cleanup scan
     * (age trim, size trim).
     */
    private val cleanupIdleIntervalMs: Long =
        Duration.ofHours(2).toMillis().coerceAtLeast(0L)

    /**
     * After thumbnail activity, debounce the next cleanup by this long. When
     * [State.ThumbCache.maxAgeMs] is set, [activityCleanupDelayMs] uses the smaller of this and that
     * max age so cleanup can run before entries expire.
     */
    private val cleanupDebounceAfterActivityMs: Long =
        Duration.ofSeconds(3).toMillis().coerceAtLeast(0L)

    // Prevents overlapping cleanup passes.
    private val isCleaning = AtomicBoolean(false)

    // Requests another cleanup pass if activity happens during cleanup.
    private val pendingCleanup = AtomicBoolean(false)

    // Guards active cache usage tracking.
    private val cacheUsageLock = Any()

    // Tracks cache files that are currently being read or written.
    private val activeCacheUsages = mutableMapOf<String, Int>()

    // Ensures only one request generates a given cache key at a time.
    private val inFlightGenerations = ConcurrentHashMap<String, CountDownLatch>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var cleanupJob: Job? = null
    private var scheduledCleanupAtMs: Long? = null

    init {
        av_log_set_level(AV_LOG_QUIET)

        // Run one cleanup immediately after startup.
        scope.launch {
            runFullCleanup()
        }
    }

    private fun activityCleanupDelayMs(): Long {
        val maxAgeMs = State.ThumbCache.maxAgeMs ?: return cleanupDebounceAfterActivityMs
        return minOf(cleanupDebounceAfterActivityMs, maxAgeMs)
    }

    private fun scheduleCleanupIn(delayMs: Long, onlyIfEarlier: Boolean = false) {
        scheduleCleanupAt(System.currentTimeMillis() + delayMs.coerceAtLeast(0L), onlyIfEarlier)
    }

    /**
     * Schedules the next full cache cleanup pass.
     */
    @Synchronized
    private fun scheduleCleanupAt(targetAtMs: Long, onlyIfEarlier: Boolean = false) {
        val existingTargetAtMs = scheduledCleanupAtMs
        if (onlyIfEarlier && existingTargetAtMs != null && existingTargetAtMs <= targetAtMs) return

        cleanupJob?.cancel()
        scheduledCleanupAtMs = targetAtMs
        cleanupJob = scope.launch {
            val delayMs = (targetAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
            delay(delayMs)

            synchronized(this@ThumbnailService) {
                if (scheduledCleanupAtMs != targetAtMs) return@launch
                scheduledCleanupAtMs = null
            }

            runFullCleanup()
        }
    }

    private fun scheduleNextCleanup(cleanupResult: CleanCacheResult) {
        val now = System.currentTimeMillis()
        val defaultCleanupAtMs = now + cleanupIdleIntervalMs

        val nextCleanupAtMs = when {
            cleanupResult.hasExpiredFilesRemaining -> now + activityCleanupDelayMs()
            cleanupResult.nextExpiryAtMs != null -> minOf(defaultCleanupAtMs, cleanupResult.nextExpiryAtMs)
            else -> defaultCleanupAtMs
        }

        scheduleCleanupAt(nextCleanupAtMs)
    }

    /**
     * Requests a near-future cleanup after thumbnail activity.
     */
    fun triggerActivityCleanup() {
        if (isCleaning.get()) {
            pendingCleanup.set(true)
        } else {
            scheduleCleanupIn(activityCleanupDelayMs(), onlyIfEarlier = true)
        }
    }

    /**
     * Runs cleanup until no more follow-up passes are needed.
     */
    private fun runFullCleanup() {
        if (!isCleaning.compareAndSet(false, true)) {
            pendingCleanup.set(true)
            return
        }

        var cleanupResult = CleanCacheResult()
        try {
            do {
                pendingCleanup.set(false)

                val cacheDirStr = State.ThumbCache.folderPath
                val cachePath = cacheDirStr?.let { Paths.get(it) }

                // Detect cache directory changes during cleanup and rerun if needed.
                val initialModifiedTime = try {
                    if (cachePath != null && Files.exists(cachePath)) {
                        Files.getLastModifiedTime(cachePath).toMillis()
                    } else 0L
                } catch (e: Exception) {
                    0L
                }

                val maxAgeMs = State.ThumbCache.maxAgeMs
                val maxSizeMb = State.ThumbCache.maxSizeMb?.toLong()
                cleanupResult = cleanCache(lastModifiedOlderThanMs = maxAgeMs, maxSizeMb = maxSizeMb)

                // If the directory changed while cleaning, do another pass.
                val finalModifiedTime = try {
                    if (cachePath != null && Files.exists(cachePath)) {
                        Files.getLastModifiedTime(cachePath).toMillis()
                    } else 0L
                } catch (e: Exception) {
                    0L
                }

                if (initialModifiedTime != 0L && initialModifiedTime != finalModifiedTime) {
                    pendingCleanup.set(true)
                }
            } while (pendingCleanup.get())
        } finally {
            isCleaning.set(false)
            if (pendingCleanup.get()) {
                scheduleCleanupIn(activityCleanupDelayMs())
            } else {
                scheduleNextCleanup(cleanupResult)
            }
        }
    }

    /**
     * Calculates JPEG quality based on the final thumbnail size.
     */
    fun calculateQuality(width: Int): Double {
        val minWidth = 200.0
        val maxWidth = 1150.0
        val maxQuality = 1.0
        val minQuality = 0.60

        if (width <= minWidth) return maxQuality
        if (width >= maxWidth) return minQuality

        val progress = (width - minWidth) / (maxWidth - minWidth)
        val quality = maxQuality - (progress * (maxQuality - minQuality))

        return Math.round(quality * 100.0) / 100.0
    }

    /**
     * Builds the cache file path for a thumbnail request.
     */
    fun getCacheFile(canonicalPathString: String, modifiedDate: Long, fileSize: Long, targetSize: Int): File? {
        val cacheEnabled = State.ThumbCache.isEnabled
        val cacheDir = State.ThumbCache.folderPath ?: return null

        if (!cacheEnabled) return null

        val isAllowed = fileVisibilityService.isPathAllowed(canonicalPath = FilePath.of(cacheDir))
        if (isAllowed != null) return null

        // Prevent caching if the file is already inside the cache directory
        if (canonicalPathString.startsWith(cacheDir)) return null

        val pathHash = md5hash(canonicalPathString)
        val cacheName = "${pathHash}_${modifiedDate}_${fileSize}_${targetSize}.jpg"
        return File(cacheDir, cacheName)
    }

    /**
     * Writes a generated thumbnail to the response and optionally to cache.
     */
    private fun saveAndStreamThumbnail(
        thumbnail: Thumbnails.Builder<*>,
        outputStream: OutputStream,
        cacheFile: File?
    ) {
        if (cacheFile != null) {
            cacheFile.parentFile?.mkdirs()
            cacheFile.outputStream().use { fileStream ->
                TeeOutputStream(fileStream, outputStream).use { teeStream ->
                    thumbnail.toOutputStream(teeStream)
                }
            }

            // Clear old cache files for this image
            val nameParts = cacheFile.nameWithoutExtension.split("_")
            if (nameParts.size == 3 || nameParts.size == 4) {
                val pathHash = nameParts[0]
                val modifiedDate = nameParts[1].toLongOrNull()
                val fileSize = if (nameParts.size == 4) nameParts[2].toLongOrNull() else null
                if (modifiedDate != null) {
                    cleanCache(targetPathHash = pathHash, keepModifiedDate = modifiedDate, keepFileSize = fileSize)
                }
            }
        } else {
            thumbnail.toOutputStream(outputStream)
        }
    }

    /**
     * Serves a valid cached thumbnail or coordinates a single generator.
     */
    private fun streamCachedOrGenerate(
        cacheFile: File?,
        outputStream: OutputStream,
        generateAndStream: (File?) -> Unit
    ) {
        if (cacheFile == null) {
            generateAndStream(null)
            return
        }

        val cacheKey = cacheFile.absolutePath

        while (true) {
            val existingGeneration = inFlightGenerations[cacheKey]
            if (existingGeneration != null) {
                // Another request is generating this exact cache entry.
                existingGeneration.await()
                continue
            }

            if (streamCachedFileIfValid(cacheFile, outputStream)) {
                return
            }

            val generationSignal = CountDownLatch(1)
            if (inFlightGenerations.putIfAbsent(cacheKey, generationSignal) != null) {
                continue
            }

            try {
                if (streamCachedFileIfValid(cacheFile, outputStream)) {
                    return
                }

                withActiveCacheFile(cacheFile) {
                    generateAndStream(cacheFile)
                }
                return
            } finally {
                inFlightGenerations.remove(cacheKey, generationSignal)
                generationSignal.countDown()
            }
        }
    }

    /**
     * Streams a cached file only if it is still readable and decodable.
     */
    private fun streamCachedFileIfValid(cacheFile: File, outputStream: OutputStream): Boolean {
        if (!cacheFile.exists() || !cacheFile.canRead()) return false

        return withActiveCacheFile(cacheFile) {
            if (!cacheFile.exists() || !cacheFile.canRead()) return@withActiveCacheFile false

            if (!isCacheFileValid(cacheFile)) {
                // Remove corrupt or partial cache files so the next path regenerates.
                runCatching { Files.deleteIfExists(cacheFile.toPath()) }
                return@withActiveCacheFile false
            }

            touchCacheFileIfStale(cacheFile)
            cacheFile.inputStream().use { it.copyTo(outputStream) }
            true
        }
    }

    /**
     * Performs a lightweight validation that a cache file is a real image.
     */
    private fun isCacheFileValid(cacheFile: File): Boolean {
        if (!cacheFile.exists() || !cacheFile.canRead() || cacheFile.length() <= 0L) return false

        return try {
            val imageInputStream = ImageIO.createImageInputStream(cacheFile) ?: return false
            imageInputStream.use {
                val readers = ImageIO.getImageReaders(it)
                if (!readers.hasNext()) return false

                val reader = readers.next()
                try {
                    reader.input = it
                    reader.getWidth(0) > 0 && reader.getHeight(0) > 0
                } finally {
                    reader.dispose()
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Marks a cache file as actively used for the duration of an action.
     */
    private fun <T> withActiveCacheFile(cacheFile: File, action: () -> T): T {
        val cacheKey = cacheFile.absolutePath

        synchronized(cacheUsageLock) {
            activeCacheUsages[cacheKey] = (activeCacheUsages[cacheKey] ?: 0) + 1
        }

        try {
            return action()
        } finally {
            synchronized(cacheUsageLock) {
                val remaining = (activeCacheUsages[cacheKey] ?: 1) - 1
                if (remaining <= 0) {
                    activeCacheUsages.remove(cacheKey)
                } else {
                    activeCacheUsages[cacheKey] = remaining
                }
            }
        }
    }

    /**
     * Deletes a cache file only when it is not currently in use.
     */
    private fun deleteCacheFileIfIdle(path: Path): Boolean {
        synchronized(cacheUsageLock) {
            if ((activeCacheUsages[path.toFile().absolutePath] ?: 0) > 0) return false
            return Files.deleteIfExists(path)
        }
    }

    /**
     * Moves a cache file to a new hash-prefixed name when neither side is in use.
     */
    private fun moveCacheFileIfIdle(sourcePath: Path, targetPath: Path): Boolean {
        val sourceKey = sourcePath.toFile().absolutePath
        val targetKey = targetPath.toFile().absolutePath

        if (inFlightGenerations.containsKey(sourceKey) || inFlightGenerations.containsKey(targetKey)) return false

        synchronized(cacheUsageLock) {
            if ((activeCacheUsages[sourceKey] ?: 0) > 0) return false
            if (!Files.exists(sourcePath)) return false

            if (Files.exists(targetPath)) {
                return Files.deleteIfExists(sourcePath)
            }

            if ((activeCacheUsages[targetKey] ?: 0) > 0) return false

            Files.move(sourcePath, targetPath)
            return true
        }
    }

    /**
     * Refreshes the cache file timestamp, but not more than once per interval.
     */
    private fun touchCacheFileIfStale(cacheFile: File) {
        val now = System.currentTimeMillis()
        val lastModified = cacheFile.lastModified()

        if (lastModified != 0L && now - lastModified < cacheTouchIntervalMs) return

        runCatching {
            cacheFile.setLastModified(now)
        }
    }

    /**
     * Verifies if a filename matches the thumbnail cache format:
     * {md5hash}_{modifiedDate}_{targetSize}.jpg OR
     * {md5hash}_{modifiedDate}_{fileSize}_{targetSize}.jpg
     */
    private fun isCacheFileName(fileName: String): Boolean {
        if (!fileName.endsWith(".jpg", ignoreCase = true)) return false
        
        val nameWithoutExtension = fileName.substringBeforeLast('.')
        val nameParts = nameWithoutExtension.split("_")
        
        if (nameParts.size != 3 && nameParts.size != 4) return false
        
        val fileHash = nameParts[0]
        if (fileHash.length != 32) return false
        
        if (nameParts[1].toLongOrNull() == null) return false
        
        if (nameParts.size == 4) {
            if (nameParts[2].toLongOrNull() == null) return false
            if (nameParts[3].toIntOrNull() == null) return false
        } else {
            if (nameParts[2].toIntOrNull() == null) return false
        }
        
        return true
    }

    fun deleteCacheForPath(path: FilePath) {
        val hash = md5hash(path.pathString)
        cleanCache(targetPathHash = hash)
    }

    fun clearCache() {
        val cacheDirStr = State.ThumbCache.folderPath ?: return
        val cachePath = Paths.get(cacheDirStr)
        if (!Files.exists(cachePath) || !Files.isDirectory(cachePath)) return

        try {
            Files.newDirectoryStream(cachePath).use { dirStream ->
                for (path in dirStream) {
                    try {
                        if (Files.isRegularFile(path)) {
                            if (isCacheFileName(path.fileName.toString())) {
                                deleteCacheFileIfIdle(path)
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }
        } catch (e: Exception) {
            RateLimitedLog.ifDue("thumbnail_cache_clear") {
                logService.error(
                    type = LogType.SYSTEM,
                    action = UserAction.NONE,
                    description = "Thumbnail cache clear failed while listing cache directory.",
                    message = e.stackTraceToString(),
                )
            }
        }
    }

    /**
     * Rekeys cached thumbnails after a file move so the cache follows the new path hash.
     */
    fun moveCacheForPath(oldPath: FilePath, newPath: FilePath) {
        val cacheDirStr = State.ThumbCache.folderPath ?: return
        val cachePath = Paths.get(cacheDirStr)
        if (!Files.exists(cachePath) || !Files.isDirectory(cachePath)) return

        val oldHash = md5hash(oldPath.pathString)
        val newHash = md5hash(newPath.pathString)

        if (oldHash == newHash) return

        try {
            Files.newDirectoryStream(cachePath, "${oldHash}_*").use { dirStream ->
                for (path in dirStream) {
                    try {
                        if (!Files.isRegularFile(path)) continue

                        val fileName = path.fileName.toString()
                        if (!isCacheFileName(fileName)) continue

                        val suffix = fileName.substringAfter("${oldHash}_", "")
                        if (suffix.isBlank()) continue

                        val targetPath = cachePath.resolve("${newHash}_${suffix}")
                        if (path == targetPath) continue

                        moveCacheFileIfIdle(sourcePath = path, targetPath = targetPath)
                    } catch (_: Exception) {
                        // Skip file if any error occurs
                    }
                }
            }
        } catch (e: Exception) {
            RateLimitedLog.ifDue("thumbnail_cache_move") {
                logService.error(
                    type = LogType.SYSTEM,
                    action = UserAction.NONE,
                    description = "Thumbnail cache rekey failed while scanning cache directory.",
                    message = e.stackTraceToString(),
                )
            }
        }
    }

    /**
     * Clears cache files based on the specified criteria.
     * 
     * @param targetPathHash If provided, limits the clearing to files matching this hash.
     * @param keepModifiedDate If provided along with targetPathHash, deletes files for this hash that have a different modification date.
     * @param keepFileSize If provided along with targetPathHash, deletes files for this hash that have a different file size or still use the legacy filename format.
     * @param lastModifiedOlderThanMs If provided, deletes any cache file whose modified time is older than this duration (in milliseconds).
     * @param maxSizeMb If provided, deletes the oldest cache files by modified time until the total cache size is below this threshold (in megabytes).
     */
    private fun cleanCache(
        targetPathHash: String? = null,
        keepModifiedDate: Long? = null,
        keepFileSize: Long? = null,
        lastModifiedOlderThanMs: Long? = null,
        maxSizeMb: Long? = null
    ): CleanCacheResult {
        val cacheDirStr = State.ThumbCache.folderPath ?: return CleanCacheResult()
        val cachePath = Paths.get(cacheDirStr)
        if (!Files.exists(cachePath) || !Files.isDirectory(cachePath)) return CleanCacheResult()

        val now = System.currentTimeMillis()

        // Optimize: if we only want to clear for a specific hash and aren't checking age or size, filter by glob.
        val globPattern = if (targetPathHash != null && lastModifiedOlderThanMs == null && maxSizeMb == null) {
            "${targetPathHash}_*"
        } else {
            "*"
        }

        val remainingFiles = mutableListOf<FileInfo>()
        var totalSizeBytes = 0L
        var nextExpiryAtMs: Long? = null
        var hasExpiredFilesRemaining = false

        try {
            Files.newDirectoryStream(cachePath, globPattern).use { dirStream ->
                for (path in dirStream) {
                    try {
                        if (!Files.isRegularFile(path)) continue

                        val fileName = path.fileName.toString()
                        if (!isCacheFileName(fileName)) continue

                        val nameWithoutExtension = fileName.substringBeforeLast('.')
                        val nameParts = nameWithoutExtension.split("_")

                        val fileHash = nameParts[0]
                        val fileModifiedDate = nameParts[1].toLongOrNull() ?: continue
                        val fileSizeInKey = if (nameParts.size == 4) nameParts[2].toLongOrNull() else null

                        // Read attributes in one system call using NIO
                        val attrs = try {
                            Files.readAttributes(path, BasicFileAttributes::class.java)
                        } catch (e: Exception) {
                            null
                        }

                        val cacheModifiedTime = attrs?.lastModifiedTime()?.toMillis() ?: path.toFile().lastModified()
                        val size = attrs?.size() ?: path.toFile().length()

                        val deleted = deleteOldOrMismatchedFiles(
                            path = path,
                            fileHash = fileHash,
                            fileModifiedDate = fileModifiedDate,
                            fileSizeInKey = fileSizeInKey,
                            targetPathHash = targetPathHash,
                            keepModifiedDate = keepModifiedDate,
                            keepFileSize = keepFileSize,
                            lastModifiedOlderThanMs = lastModifiedOlderThanMs,
                            cacheModifiedTime = cacheModifiedTime,
                            now = now
                        )

                        if (!deleted &&
                            targetPathHash == null &&
                            keepModifiedDate == null &&
                            keepFileSize == null &&
                            lastModifiedOlderThanMs != null
                        ) {
                            val expiresAtMs = cacheModifiedTime + lastModifiedOlderThanMs
                            if (expiresAtMs <= now) {
                                hasExpiredFilesRemaining = true
                            } else {
                                val previousNextExpiryAtMs = nextExpiryAtMs
                                if (previousNextExpiryAtMs == null || expiresAtMs < previousNextExpiryAtMs) {
                                    nextExpiryAtMs = expiresAtMs
                                }
                            }
                        }

                        if (!deleted && maxSizeMb != null) {
                            remainingFiles.add(FileInfo(path, size, cacheModifiedTime))
                            totalSizeBytes += size
                        }
                    } catch (e: Exception) {
                        // Skip file if any error occurs
                    }
                }
            }
        } catch (e: Exception) {
            RateLimitedLog.ifDue("thumbnail_cache_clean_scan") {
                logService.error(
                    type = LogType.SYSTEM,
                    action = UserAction.NONE,
                    description = "Thumbnail cache cleanup failed while scanning cache directory.",
                    message = e.stackTraceToString(),
                )
            }
            return CleanCacheResult(nextExpiryAtMs = nextExpiryAtMs, hasExpiredFilesRemaining = hasExpiredFilesRemaining)
        }

        // 3. Clear by max size
        if (maxSizeMb != null) {
            enforceMaxSize(remainingFiles, maxSizeMb, totalSizeBytes)
        }

        return CleanCacheResult(nextExpiryAtMs = nextExpiryAtMs, hasExpiredFilesRemaining = hasExpiredFilesRemaining)
    }

    private data class CleanCacheResult(
        val nextExpiryAtMs: Long? = null,
        val hasExpiredFilesRemaining: Boolean = false,
    )

    /**
     * Small record used when trimming the cache by total size.
     */
    class FileInfo(val path: Path, val size: Long, val cacheModifiedTime: Long)

    /**
     * Applies targeted invalidation and age-based expiration to one cache file.
     */
    private fun deleteOldOrMismatchedFiles(
        path: Path,
        fileHash: String,
        fileModifiedDate: Long,
        fileSizeInKey: Long?,
        targetPathHash: String?,
        keepModifiedDate: Long?,
        keepFileSize: Long?,
        lastModifiedOlderThanMs: Long?,
        cacheModifiedTime: Long,
        now: Long
    ): Boolean {
        // 1. Clear old path if the image has a different modification date
        if (targetPathHash != null && fileHash == targetPathHash) {
            val matchesModifiedDate = keepModifiedDate == null || fileModifiedDate == keepModifiedDate
            val matchesFileSize = keepFileSize == null || fileSizeInKey == keepFileSize

            if (!matchesModifiedDate || !matchesFileSize) {
                return deleteCacheFileIfIdle(path)
            } else if (keepModifiedDate == null && keepFileSize == null) {
                // Just clear by hash
                return deleteCacheFileIfIdle(path)
            }
        }

        // 2. Clear by cache file modified time
        if (lastModifiedOlderThanMs != null) {
            if (now - cacheModifiedTime > lastModifiedOlderThanMs) {
                return deleteCacheFileIfIdle(path)
            }
        }
        return false
    }

    /**
     * Deletes the oldest cached files until the cache fits within the size limit.
     */
    private fun enforceMaxSize(remainingFiles: List<FileInfo>, maxSizeMb: Long, totalSizeBytes: Long) {
        val maxSizeBytes = maxSizeMb * 1024 * 1024
        var currentSize = totalSizeBytes

        if (currentSize > maxSizeBytes) {
            // Sort by cache modified time (oldest first)
            val sortedFiles = remainingFiles.sortedBy { it.cacheModifiedTime }

            for (info in sortedFiles) {
                if (currentSize <= maxSizeBytes) break
                try {
                    if (deleteCacheFileIfIdle(info.path)) {
                        currentSize -= info.size
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Streams an image thumbnail, using the cache when possible.
     */
    fun streamImageThumbnail(
        canonicalPathString: String,
        targetSize: Int,
        modifiedDate: Long,
        fileSize: Long,
        inputStreamProvider: () -> InputStream,
        outputStream: OutputStream
    ) {
        triggerActivityCleanup()
        
        val cacheFile = getCacheFile(canonicalPathString, modifiedDate, fileSize, targetSize)

        streamCachedOrGenerate(cacheFile, outputStream) { targetCacheFile ->
            val inputStream = inputStreamProvider()

            try {
                // Read the source image once so Thumbnailator can work with a BufferedImage.
                val image = inputStream.use {
                    Thumbnails.of(it).scale(1.0).asBufferedImage()
                }

                val sourceSize = max(image.width, image.height)
                val finalSize = min(sourceSize, targetSize)

                val thumbnail = Thumbnails.of(image)
                    .outputFormat("jpg")
                    .outputQuality(calculateQuality(finalSize))

                // Only apply size reduction if the image exceeds the bounds
                if (sourceSize > targetSize) {
                    thumbnail.size(targetSize, targetSize).keepAspectRatio(true)
                } else {
                    // Keep original dimensions and only compress/convert.
                    thumbnail.scale(1.0)
                }

                saveAndStreamThumbnail(thumbnail, outputStream, targetCacheFile)
            } catch (e: Exception) {
                // Fallback to ffmpeg for unsupported formats like AVIF/HEIC
                fallbackFfmpegImageThumbnail(canonicalPathString, targetSize, outputStream, targetCacheFile)
            }
        }
    }

    /**
     * Uses ffmpeg as a fallback for image formats Thumbnailator cannot decode.
     */
    private fun fallbackFfmpegImageThumbnail(
        canonicalPathString: String,
        targetSize: Int,
        outputStream: OutputStream,
        cacheFile: File?
    ) {
        val imageFile = File(canonicalPathString)
        val grabber = FFmpegFrameGrabber(imageFile)
        grabber.start()

        try {
            val frame = grabber.grabImage() ?: throw Exception("Could not decode image")
            val converter = Java2DFrameConverter()
            val image = converter.convert(frame) ?: throw Exception("Could not convert frame to image")

            val sourceSize = max(image.width, image.height)
            val finalSize = min(sourceSize, targetSize)

            val thumbnail = Thumbnails.of(image)
                .outputFormat("jpg")
                .outputQuality(calculateQuality(finalSize))
                .scalingMode(ScalingMode.BILINEAR)

                if (sourceSize > targetSize) {
                    thumbnail.size(targetSize, targetSize).keepAspectRatio(true)
                } else {
                    // Keep original dimensions and only compress/convert.
                    thumbnail.scale(1.0)
                }

                saveAndStreamThumbnail(thumbnail, outputStream, cacheFile)
        } catch (e: Exception) {
            RateLimitedLog.ifDue("thumbnail_cache_ffmpeg_image") {
                logService.error(
                    type = LogType.SYSTEM,
                    action = UserAction.NONE,
                    description = "FFmpeg fallback failed while generating an image thumbnail.",
                    message = e.stackTraceToString(),
                )
            }
            outputStream.write("Error generating image thumbnail".toByteArray())
        } finally {
            grabber.stop()
            grabber.release()
        }
    }

    /**
     * Streams a video preview frame, using the cache when possible.
     */
    fun streamVideoPreview(
        canonicalPathString: String,
        targetSize: Int,
        modifiedDate: Long,
        fileSize: Long,
        outputStream: OutputStream
    ) {
        triggerActivityCleanup()

        val cacheFile = getCacheFile(canonicalPathString, modifiedDate, fileSize, targetSize)

        streamCachedOrGenerate(cacheFile, outputStream) { targetCacheFile ->
            val videoFile = File(canonicalPathString)

            if (!videoFile.exists() || !videoFile.canRead()) {
                outputStream.write("File not found or cannot be read".toByteArray())
                return@streamCachedOrGenerate
            }

            val grabber = FFmpegFrameGrabber(videoFile)
            grabber.start()

            try {
                val frame = grabber.grabImage()

                if (frame == null) {
                    outputStream.write("Could not extract frame from video".toByteArray())
                    return@streamCachedOrGenerate
                }

                val converter = Java2DFrameConverter()
                val originalImage = converter.convert(frame)

                if (originalImage == null) {
                    outputStream.write("Invalid video format or frame".toByteArray())
                    return@streamCachedOrGenerate
                }
                val sourceSize = max(originalImage.width, originalImage.height)
                val finalSize = min(sourceSize, targetSize)

                val thumbnail = Thumbnails.of(originalImage)
                    .outputFormat("jpg")
                    .outputQuality(calculateQuality(finalSize))

                if (sourceSize > targetSize) {
                    thumbnail.size(targetSize, targetSize).keepAspectRatio(true)
                } else {
                    // Keep original dimensions and only compress/convert.
                    thumbnail.scale(1.0)
                }

                saveAndStreamThumbnail(thumbnail, outputStream, targetCacheFile)
            } catch (e: Exception) {
                RateLimitedLog.ifDue("thumbnail_cache_ffmpeg_video") {
                    logService.error(
                        type = LogType.SYSTEM,
                        action = UserAction.NONE,
                        description = "Failed while generating a video preview thumbnail.",
                        message = e.stackTraceToString(),
                    )
                }
                outputStream.write("Error generating video preview".toByteArray())
            } finally {
                grabber.stop()
                grabber.release()
            }
        }
    }
}
