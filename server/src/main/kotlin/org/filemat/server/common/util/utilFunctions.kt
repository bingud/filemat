package org.filemat.server.common.util

import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.serialization.json.*
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.config.TransactionTemplateConfig
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.log.service.LogService
import org.springframework.transaction.TransactionStatus
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureNanoTime

fun unixNow() = Instant.now().epochSecond


fun List<String>?.toJsonOrNull(): String? {
    this ?: return null
    return Json.encodeToString(this)
}


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


fun parseUlidOrNull(str: String): Ulid? = runCatching { Ulid.from(str) }.getOrNull()


fun getUlid() = UlidCreator.getUlid()


fun Int.toBoolean() = this > 0
fun Short.toBoolean() = this > 0


fun HttpServletRequest.getPrincipal() = this.getAttribute("auth") as Principal?

fun HttpServletRequest.realIp(): String {
    val header: String? = this.getHeader("X-Forwarded-For")
    return header ?: this.remoteAddr
}


fun <K, V> ConcurrentHashMap<K, V>.removeIf(block: (key: K, value: V) -> Boolean) {
    val iterator = this.entries.iterator()
    while (iterator.hasNext()) {
        val entry = iterator.next()
        if (block(entry.key, entry.value)) {
            iterator.remove()
        }
    }
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

fun printlns(vararg texts: Any?): Unit {
    println(texts.joinToString("\n"))
}

/**
 * Fully normalizes a path, makes it absolute
 */
fun String.getNormalizedPath(): Path = Paths.get("/").resolve(this.trimStart('/')).normalize()
fun Path.getNormalizedPath(): Path =
    if (this.isAbsolute) normalize()
    else Paths.get("/").resolve(this).normalize()

fun String.normalizePath() = this.getNormalizedPath().toString()


fun String.addPrefixIfNotPresent(prefix: Char) = if (this.startsWith(prefix)) this else prefix + this


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


/**
 * Returns:
 *
 * - Resolved, canonical file path
 *
 * - Whether the error was caused because the path had a symlink.
 */
fun resolvePath(filePath: FilePath): Pair<Result<FilePath>, Boolean> {
    return try {
        val (canonicalPath, containsSymlink) = if (State.App.followSymLinks) {
            filePath.path.toRealPath() to false
        } else {
            val containsSymlink = pathContainsSymlink(filePath.path)
            filePath.path.toRealPath() to containsSymlink
        }

        Result.ok(FilePath.ofAlreadyNormalized(canonicalPath)) to containsSymlink
    } catch (e: NoSuchFileException) {
        Pair(Result.notFound(), false)
    } catch (e: Exception) {
        Pair(Result.error("Failed to resolve path"), false)
    }
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

    // Generic put that serializes any object into a JsonElement:
    inline fun <reified T> put(key: String, value: T) {
        content[key] = Json.encodeToJsonElement(value)
    }

    fun build() = JsonObject(content)
    override fun toString() = build().toString()
}

fun json(block: JsonBuilder.() -> Unit): String {
    return JsonBuilder().apply { block() }.toString()
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