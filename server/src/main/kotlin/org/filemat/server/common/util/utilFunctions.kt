package org.filemat.server.common.util

import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.serialization.json.*
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.toResult
import org.filemat.server.config.Props
import org.filemat.server.config.TransactionTemplateConfig
import org.filemat.server.config.platform.PathPolicy
import org.filemat.server.config.platform.Platform
import org.filemat.server.config.platform.WindowsNative
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.log.service.LogService
import org.springframework.transaction.TransactionStatus
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.system.measureNanoTime

fun unixNow() = Instant.now().epochSecond


fun List<String>?.toJsonOrNull(): String? {
    this ?: return null
    return Json.encodeToString(this)
}

fun List<String>.toJson() = Json.encodeToString(this)

inline fun <reified T> Json.decodeFromStringOrNull(string: String): T? {
    return try {
        Json.decodeFromString<T>(string)
    } catch (e: Exception) {
        null
    }
}

fun getParentFromPath(path: FilePath): FilePath {
    return FilePath.ofAlreadyNormalized(path.path.parent)
}

fun getFilenameFromPath(path: Path): String {
    return path.fileName.toString()
}

inline fun <reified T> String.parseJsonOrNull(): T? {
    return Json.decodeFromStringOrNull<T>(this)
}

fun <K, V> Map<K, V>.getAll(keys: Collection<K>): List<V> {
    val list = mutableListOf<V>()
    keys.forEach { key ->
        val value = this[key]
        if (value != null) list.add(value)
    }
    return list
}

fun String.splitByLast(delimiter: String): Pair<String, String?> {
    val idx = lastIndexOf(delimiter)
    return if (idx >= 0) {
        val before = substring(0, idx)
        val after  = substring(idx + delimiter.length)
        before to after
    } else {
        this to null
    }
}


inline fun <T> measureNano(block: () -> T): Pair<T, Long> {
    var result: T

    val nano = measureNanoTime {
        result = block()
    }

    return result to nano
}


inline fun <T: Any?> measureMillis(block: () -> T): Pair<T, Double> {
    return measureNano(block).let { it.first to it.second.toDouble() / 1_000_000 }

}

fun Boolean.toInt() = if (this) 1 else 0


fun <T> runTransaction(block: (status: TransactionStatus) -> T): T {
    val result = TransactionTemplateConfig.instance.execute { status ->
        block(status)
    }

    return result!!
}


inline fun <reified E : Enum<E>> valueOfOrNull(name: String): E? {
    return enumValues<E>().find { it.name == name }
}


inline fun <T> runIf(condition: Boolean, block: () -> T): T? {
    return if (condition) block() else null
}


fun parseUlidOrNull(str: String?): Ulid? {
    str ?: return null
    return runCatching { Ulid.from(str) }.getOrNull()
}


fun getUlid() = UlidCreator.getUlid()


fun Int.toBoolean() = this > 0
fun Short.toBoolean() = this > 0


fun HttpServletRequest.getPrincipal() = this.getAttribute("auth") as Principal?

fun HttpServletRequest.realIp(): String {
    val header: String? = this.getHeader("X-Forwarded-For")
    return header ?: this.remoteAddr
}


fun <K, V> ConcurrentHashMap<K, V>.removeIf(block: (key: K, value: V) -> Boolean): V? {
    val iterator = this.entries.iterator()
    while (iterator.hasNext()) {
        val entry = iterator.next()
        if (block(entry.key, entry.value)) {
            iterator.remove()
            return entry.value
        }
    }
    return null
}


fun <K, V> ConcurrentHashMap<K, V>.iterate(block: (key: K, value: V, remove: () -> Unit) -> Unit) {
    val iterator = this.entries.iterator()
    while (iterator.hasNext()) {
        val entry = iterator.next()
        block(entry.key, entry.value, iterator::remove)
    }
}

fun HttpServletResponse.respond(statusCode: Int, message: String) {
    this.status = statusCode
    this.writer.write(message)
}

private val unixFilenameFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault())
fun formatUnixToFilename(instant: Instant): String {
    return unixFilenameFormatter.format(instant)
}

fun formatMillisecondsToReadableTime(ms: Long): String {
    return when {
        ms >= 86_400_000L -> {
            val days = ms / 86_400_000L
            "$days day" + if (days == 1L) "" else "s"
        }
        ms >= 3_600_000L -> {
            val hours = ms / 3_600_000L
            "$hours hour" + if (hours == 1L) "" else "s"
        }
        ms >= 60_000L -> {
            val minutes = ms / 60_000L
            "$minutes minute" + if (minutes == 1L) "" else "s"
        }
        ms >= 1_000L -> {
            val seconds = ms / 1_000L
            "$seconds second" + if (seconds == 1L) "" else "s"
        }
        else -> "$ms millisecond" + if (ms == 1L) "" else "s"
    }
}

fun formatSecondsToReadableTime(s: Long): String {
    return formatMillisecondsToReadableTime(s * 1000)
}

fun printlns(vararg texts: Any?): Unit {
    println(texts.joinToString("\n"))
}

fun formatMillisToSeconds(millis: Long): String {
    val seconds = millis / 1000.0
    return String.format("%.2f", seconds)
}

fun todo() { runCatching { TODO() }.onFailure { it.printStackTrace() } }

fun State.runDev(block: () -> Unit) {
    if (!app.isDev) return
    runCatching {
        block()
    }
}

/**
 * Fully normalizes a path, makes it absolute
 */
fun String.getNormalizedPath(): Path = PathPolicy.parse(this)
fun Path.getNormalizedPath(): Path = PathPolicy.normalize(this)

/**
 * Normalizes a path string into the API format
 */
fun String.normalizePath() = PathPolicy.toApiString(this.getNormalizedPath())


fun String.isAbsoluteHttpsUrl(): Boolean {
    if (isBlank()) return false
    return try {
        val uri = URI(trim())
        uri.scheme?.lowercase() == "https"
            && !uri.host.isNullOrBlank()
            && uri.rawQuery == null
            && uri.rawFragment == null
    } catch (_: Exception) {
        false
    }
}


fun parseTusHttpHeader(header: String): Map<String, String> {
    return header.split(",").mapNotNull {
        val parts = it.trim().split(" ", limit = 2)
        if (parts.size == 2) {
            try {
                val key = parts[0]
                val decodedValue = String(Base64.getDecoder().decode(parts[1]))
                key to decodedValue
            } catch (_: IllegalArgumentException) {
                null // Skip invalid base64
            }
        } else null
    }.toMap()
}

fun encodeToBase64(input: String): String {
    return Base64.getEncoder()
        .encodeToString(input.toByteArray(Charsets.UTF_8))
}

fun <T> concurrentMutableSetOf(): ConcurrentHashMap.KeySetView<T, Boolean> = ConcurrentHashMap.newKeySet()

/**
 * Returns `Boolean` if the element already existed
 */
fun <T> MutableSet<T>.replace(element: T): Boolean {
    return this.remove(element).also { this.add(element) }
}

fun <K, V> HashMap<K, V>.removeAll(keys: Collection<K>): Boolean {
    return this.keys.removeAll(keys)
}



fun resolvePath(rawPath: FilePath, followSymlinks: Boolean, allowNonExistent: Boolean = false): Result<FilePath> {
    // The Windows drive list is not a real folder, and `toRealPath` would resolve it to the current drive
    if (PathPolicy.isVirtualRoot(rawPath.path)) return rawPath.toResult()
    // Paths without a drive on Windows are only valid inside a share
    if (!rawPath.path.isAbsolute) return Result.notFound()
    // Network drives are never opened, and `subst` drives resolve to their folder (§4.3, Q34)
    val inputPath = if (Platform.isWindows) localDrivePath(rawPath) ?: return Result.notFound() else rawPath
    // Checked before `toRealPath`, which would already connect
    if (reachesNetwork(inputPath.path)) return Result.notFound()

    try {
        val resolvedPath = FilePath.ofAlreadyNormalized(
            // Catch if file not found
            try {
                inputPath.path.toRealPath()
            } catch (e: NoSuchFileException) {
                if (allowNonExistent) return resolveNonExistent(inputPath, followSymlinks)
                return Result.notFound()
            }
        )
        if (isUncPath(resolvedPath.path) || isOnNetworkDrive(resolvedPath.path)) return Result.notFound()

        if (followSymlinks) {
            return resolvedPath.toResult()
        } else {
            val matching = PathPolicy.isSamePath(inputPath.path, resolvedPath.path)
            if (!matching) return Result.notFound()
            return resolvedPath.toResult()
        }
    } catch (e: Exception) {
        return Result.error("Failed to resolve file path.")
    }
}

/**
 * Windows: moves a path on a `subst` drive to the folder the drive points to.
 * Null for a network drive, a drive whose type can't be read, or a `subst` drive on a network folder. Neither check reaches the network.
 * A `subst` drive can point to a folder on another `subst` or network drive, so the folder it points to is checked the same way.
 */
internal fun localDrivePath(path: FilePath, depth: Int = 0): FilePath? {
    val root = path.path.root ?: return path
    if (WindowsNative.isOnNetworkDrive(path.path) != false) return null
    val target = WindowsNative.substTarget(root.toString().trimEnd('\\')) ?: return path
    if (target.startsWith("UNC\\") || depth >= maxSubstDepth) return null
    return localDrivePath(FilePath.ofAlreadyNormalized(PathPolicy.normalize(Paths.get(target).resolve(root.relativize(path.path)))), depth + 1)
}

// One per drive letter
private const val maxSubstDepth = 26

/**
 * Windows: the path with the real name of each existing segment, without following links.
 * An 8.3 short name (`CREDEN~1`) or a name in another case names the same file, but the block lists compare names,
 * so a path that is checked without [resolvePath] (a link itself, or the last name of a move) must use the real names.
 * A path that does not exist, and every path on other systems, is returned as it is.
 */
fun withRealNames(path: FilePath): FilePath {
    if (!Platform.isWindows || PathPolicy.isVirtualRoot(path.path) || !path.path.isAbsolute) return path
    val real = try {
        path.path.toRealPath(LinkOption.NOFOLLOW_LINKS)
    } catch (e: Exception) {
        return path
    }
    return FilePath.ofAlreadyNormalized(PathPolicy.normalize(real))
}

/**
 * A link to a network folder resolves to a UNC path (`\\server\share`), which is never served
 */
private fun isUncPath(path: Path) = PathPolicy.isUncPath(path.toString())

/**
 * Windows: whether [path] is on a network drive (`Z:`), a `subst` drive of a network folder, or a drive whose type can't be read
 */
private fun isOnNetworkDrive(path: Path) = Platform.isWindows && localDrivePath(FilePath.ofAlreadyNormalized(path)) == null

private const val maxLinkDepth = 40

/**
 * Windows: whether following the symbolic links in [path] leads to a network folder (`\\server\share`) or a network drive (`Z:`).
 * Opening such a link makes Windows connect and send the account's credentials, before the result can be checked,
 * so the links are read without being followed. Junctions and mount points can't point to the network.
 * A link that can't be read, or too many links in a row, count as the network.
 */
fun reachesNetwork(path: Path, depth: Int = 0): Boolean {
    if (!Platform.isWindows) return false
    if (depth > maxLinkDepth || isUncPath(path)) return true
    var current = path.root ?: return false
    for (index in 0 until path.nameCount) {
        current = current.resolve(path.getName(index))
        if (!Files.isSymbolicLink(current)) continue
        val target = runCatching { Files.readSymbolicLink(current) }.getOrNull() ?: return true
        var next = current.parent.resolve(target)
        // A link can point to another drive, which can be a mapped network drive
        if (next.root != current.root && isOnNetworkDrive(next)) return true
        if (index + 1 < path.nameCount) next = next.resolve(path.subpath(index + 1, path.nameCount))
        return reachesNetwork(next.normalize(), depth + 1)
    }
    return false
}

/**
 * Resolves all segments of file path that actually exist
 */
private fun resolveNonExistent(rawPath: FilePath, followSymlinks: Boolean): Result<FilePath> {
    // Input path: /a/b/c/d

    // Prefix of path that does exist
    // /a/b
    var lowestExistingPath: Path = rawPath.path

    // Suffix of path that does not exist
    // c/d
    var nonExistentSuffix: Path? = null

    while (true) {
        val exists = lowestExistingPath.exists(LinkOption.NOFOLLOW_LINKS)

        if (exists) {
            break
        } else {
            // Set the
            if (nonExistentSuffix == null) {
                nonExistentSuffix = lowestExistingPath.fileName
            } else {
                nonExistentSuffix = lowestExistingPath.fileName.resolve(nonExistentSuffix)
            }

            lowestExistingPath = lowestExistingPath.parent ?: return Result.notFound()
        }
    }

    val resolvedPrefixPath = try {
        lowestExistingPath.toRealPath()
    } catch (e: NoSuchFileException) {
        return Result.notFound()
    } catch (e: Exception) {
        return Result.error("Failed to resolve path that exists partially.")
    }

    val resolvedPath = if (nonExistentSuffix != null) resolvedPrefixPath.resolve(nonExistentSuffix) else resolvedPrefixPath
    if (isUncPath(resolvedPath) || isOnNetworkDrive(resolvedPath)) return Result.notFound()
    if (!followSymlinks && !PathPolicy.isSamePath(rawPath.path, resolvedPath)) {
        return Result.notFound()
    }

    return FilePath.ofAlreadyNormalized(resolvedPath).toResult()
}


fun safeStreamSkip(stream: InputStream, skip: Long): Boolean {
    var toSkip = skip
    while (toSkip > 0) {
        val skipped = stream.skip(toSkip)
        if (skipped <= 0) return false
        toSkip -= skipped
    }
    return true
}


/**
 * Returns whether any part of input path is a symlink
 */
fun pathContainsSymlink(input: Path): Boolean {
    // Start from the root (or current working dir for relative paths)
    var current = when {
        input.isAbsolute -> input.root
        else             -> Paths.get("")
    } ?: Paths.get("")

    for (segment in input.iterator()) {
        current = current.resolve(segment)

        // If this component is a symlink, bail out
        if (Files.isSymbolicLink(current)) {
            return true
        }
    }
    return false
}


fun String.toFilePath() = FilePath.of(this)

fun <T> T?.print() = this.also { println(this) }


class JsonBuilder {
    val content = linkedMapOf<String, JsonElement>()

    // You can still keep the specialized put() for primitives if you want:
    fun put(key: String, element: String) = content.put(key, Json.encodeToJsonElement(element))
    fun put(key: String, element: Int) = content.put(key, Json.encodeToJsonElement(element))
    fun put(key: String, element: Boolean) = content.put(key, Json.encodeToJsonElement(element))
    fun put(key: String, element: JsonElement) = content.put(key, element)

    // Generic put that serializes any object into a JsonElement:
    inline fun <reified T> put(key: String, value: T) {
        content[key] = Json.encodeToJsonElement(value)
    }

    inline fun <reified T> putNonNull(key: String, value: T) {
        content[key] = JsonNonNull.encodeToJsonElement<T>(value)
    }

    fun build() = JsonObject(content)
    override fun toString() = build().toString()

    override fun hashCode(): Int {
        return content.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as JsonBuilder

        return content == other.content
    }
}

fun json(block: JsonBuilder.() -> Unit): String {
    return JsonBuilder().apply { block() }.toString()
}

/**
 * Parses a simple HTTP Range header for bytes.
 * Returns a LongRange or null if the header is invalid.
 *
 * Supports:
 *  - "bytes=start-end"
 *  - "bytes=start-"
 *  - "bytes=-suffixLength"
 */
fun parseRangeHeaderToLongRange(
    rangeHeader: String,
    length: Long
): LongRange? {
    // empty resources can’t be ranged
    if (length <= 0) return null

    if (!rangeHeader.startsWith("bytes=")) return null

    // strip prefix and split into exactly two parts
    val rangeValue = rangeHeader.removePrefix("bytes=").trim()
    val parts = rangeValue.split("-", limit = 2)
    if (parts.size != 2) return null

    val startPart = parts[0].trim()
    val endPart = parts[1].trim()

    // Parse start, if any
    val start: Long? = when {
        startPart.isEmpty() -> null
        else -> {
            val s = startPart.toLongOrNull() ?: return null
            when {
                s < 0 -> 0
                s > length - 1 -> return null    // start beyond end is unsatisfiable
                else -> s
            }
        }
    }

    // Parse end, if any
    val end: Long? = when {
        endPart.isEmpty() -> null
        else -> {
            val e = endPart.toLongOrNull() ?: return null
            if (e < 0) return null              // negative end is invalid
            if (e > length - 1) length - 1 else e
        }
    }

    return when {
        // bytes=start-end
        start != null && end != null -> {
            if (end < start) return null
            start..end
        }
        // bytes=start-
        start != null && end == null -> {
            start..(length - 1)
        }
        // bytes=-suffixLength
        start == null && end != null -> {
            if (end == 0L) return null
            val suffixLen = end
            val actualStart = if (suffixLen >= length) 0 else length - suffixLen
            actualStart..(length - 1)
        }
        else -> null
    }
}


private fun gPackagePrefix(): String {
    val stackTrace = Throwable().stackTrace
    if (stackTrace.size > 1) {
        val caller = stackTrace[1]
        return "${caller.className}.${caller.methodName}".split('.').take(3).joinToString(".")
    }
    return "Unknown"
}

val packagePrefix = gPackagePrefix() + "."
fun getPackage(): String {
    val stackTrace = Throwable().stackTrace
    if (stackTrace.size > 1) {
        val caller = stackTrace[1]
        return "${caller.className}.${caller.methodName}".removePrefix(packagePrefix)
    }
    return "Unknown"
}

fun getActualCallerPackage(): String {
    val stackTrace = Throwable().stackTrace.let { it.takeLast(it.size - 1) }

    // Skip frames that belong to LogService
    for (frame in stackTrace) {
        if (!frame.className.startsWith(LogService::class.qualifiedName!!)) {
            return "${frame.className}.${frame.methodName}"
        }
    }

    return "Unknown"
}

fun HttpServletRequest.getAuthToken(): String? {
    return this.cookies?.find { it.name == Props.Cookies.authToken }?.value
}

inline fun <reified T : Enum<T>> parseEnumList(str: String?): List<T> {
    str ?: return emptyList()
    val names = Json.decodeFromString<List<String>>(str)
    return names.map { enumValueOf<T>(it) }
}

fun encodePathSegment(input: String): String {
    return URLEncoder.encode(input, StandardCharsets.UTF_8.toString())
        .replace("+", "%20")
}

fun md5hash(str: String): String {
    return MessageDigest.getInstance("MD5")
        .digest(str.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}