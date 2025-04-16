package org.filemat.server.common.util

import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator
import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.*
import org.filemat.server.config.TransactionTemplateConfig
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.log.service.LogService
import org.springframework.transaction.TransactionStatus
import java.nio.file.Paths
import java.time.Instant
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

inline fun <reified T> String.parseJsonOrNull(): T? {
    return Json.decodeFromStringOrNull<T>(this)
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

fun String.normalizePath() = Paths.get(this.addPrefixIfNotPresent('/')).normalize().toString()

fun String.addPrefixIfNotPresent(prefix: Char) = if (this.startsWith(prefix)) this else prefix + this

////

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

fun String.toFilePath() = FilePath(this)

fun <T> T.print() = println(this)

// SHITERS

private fun gPackagePrefix(): String {
    val stackTrace = Throwable().stackTrace
    if (stackTrace.size > 1) {
        val caller = stackTrace[1]
        return "${caller.className}.${caller.methodName}".split('.').take(3).joinToString(".")
    }
    return "Unknown"
}

////

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