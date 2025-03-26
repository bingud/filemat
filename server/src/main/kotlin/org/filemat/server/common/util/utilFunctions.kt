package org.filemat.server.common.util

import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator
import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.filemat.server.config.TransactionTemplateConfig
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.model.UserAction
import org.springframework.transaction.TransactionStatus
import java.nio.file.Paths
import java.time.Instant
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

fun <T> measureNano(block: () -> T): Pair<T, Long> {
    var result: T

    val nano = measureNanoTime {
        result = block()
    }

    return result to nano
}

fun <T> measureMillis(block: () -> T): Pair<T, Double> {
    return measureNano(block).let { it.first to it.second.toDouble() / 1_000_000 }
}

fun Boolean.toInt() = if (this) 1 else 0

fun <T> runTransaction(block: (status: TransactionStatus) -> T): T {
    val result = TransactionTemplateConfig.instance.execute { status ->
        block(status)
    }

    return result!!
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

fun String.normalizePath() = Paths.get(this).normalize().toString()

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
    private val content = linkedMapOf<String, JsonElement>()

    fun put(key: String, element: JsonElement) = content.put(key, element)
    fun put(key: String, element: String) = content.put(key, JsonPrimitive(element))
    fun put(key: String, element: Int) = content.put(key, JsonPrimitive(element))
    fun put(key: String, element: Boolean) = content.put(key, JsonPrimitive(element))
    fun put(key: String, element: Any) = content.put(key, JsonPrimitive(element.toString()))

    fun build() = JsonObject(content)
    override fun toString() = build().toString()
}

fun json(block: JsonBuilder.() -> Unit): String {
    return JsonBuilder().apply { block() }.toString()
}