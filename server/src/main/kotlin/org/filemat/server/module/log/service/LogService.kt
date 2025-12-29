package org.filemat.server.module.log.service

import com.github.f4b6a3.ulid.Ulid
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.toResult
import org.filemat.server.common.util.dto.RequestMeta
import org.filemat.server.common.util.getActualCallerPackage
import org.filemat.server.common.util.unixNow
import org.filemat.server.module.admin.model.LogMeta
import org.filemat.server.module.log.model.Log
import org.filemat.server.module.log.model.LogLevel
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.repository.LogRepository
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

fun meta(vararg input: Pair<String, String>) = mutableMapOf(*input)

@Service
class LogService(
    private val logRepository: LogRepository
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lastLogTimes = ConcurrentHashMap<String, Long>()
    private val rateLimitMillis = 10_000L // 10 seconds

    /**
     * Log service utilities / premade log methods
     */
    inner class Util {
        fun logServiceException(message: String, exception: Exception, userAction: UserAction, meta: MutableMap<String, String>? = null) {
            error(
                type = LogType.SYSTEM,
                action = userAction,
                description = message,
                message = exception.stackTraceToString(),
                initiatorId = null,
                initiatorIp = null,
                targetId = null,
                meta = meta
            )
        }
    }
    val util = Util()

    fun debug(type: LogType, action: UserAction, description: String, message: String = "", initiatorId: Ulid? = null, initiatorIp: String? = null, targetId: Ulid? = null, meta: MutableMap<String, String>? = null,) { log(level = LogLevel.DEBUG, type = type, action = action, description = description, message = message, initiatorId = initiatorId, initiatorIp = initiatorIp, targetId = targetId, meta = meta,) }

    fun info(type: LogType, action: UserAction, description: String, message: String = "", initiatorId: Ulid? = null, initiatorIp: String? = null, targetId: Ulid? = null, meta: MutableMap<String, String>? = null,) { log(level = LogLevel.INFO, type = type, action = action, description = description, message = message, initiatorId = initiatorId, initiatorIp = initiatorIp, targetId = targetId, meta = meta,) }

    fun warn(type: LogType, action: UserAction, description: String, message: String = "", initiatorId: Ulid? = null, initiatorIp: String? = null, targetId: Ulid? = null, meta: MutableMap<String, String>? = null,) { log(level = LogLevel.WARN, type = type, action = action, description = description, message = message, initiatorId = initiatorId, initiatorIp = initiatorIp, targetId = targetId, meta = meta,) }

    fun error(type: LogType, action: UserAction, description: String, message: String = "", initiatorId: Ulid? = null, initiatorIp: String? = null, targetId: Ulid? = null, meta: MutableMap<String, String>? = null,) { log(level = LogLevel.ERROR, type = type, action = action, description = description, message = message, initiatorId = initiatorId, initiatorIp = initiatorIp, targetId = targetId, meta = meta,) }

    fun fatal(type: LogType, action: UserAction, description: String, message: String = "", initiatorId: Ulid? = null, initiatorIp: String? = null, targetId: Ulid? = null, meta: MutableMap<String, String>? = null,) { log(level = LogLevel.FATAL, type = type, action = action, description = description, message = message, initiatorId = initiatorId, initiatorIp = initiatorIp, targetId = targetId, meta = meta,) }

    @PreDestroy
    fun onShutdown() {
        runBlocking {
            scope.coroutineContext[Job]?.children?.forEach { it.join() }
        }
    }

    private fun log(
        level: LogLevel,
        type: LogType,
        action: UserAction,
        description: String,
        message: String,
        initiatorId: Ulid? = null,
        initiatorIp: String? = null,
        targetId: Ulid? = null,
        meta: MutableMap<String, String>?,
    ) {
        val caller = getActualCallerPackage()
        val now = unixNow()

        // Rate limiter
        val currentTime = System.currentTimeMillis()

        if (type == LogType.SYSTEM) {
            val lastLogTime = lastLogTimes[caller] ?: 0L
            if (currentTime - lastLogTime < rateLimitMillis) {
                return // Skip logging due to rate limiting
            }
            lastLogTimes[caller] = currentTime
        }

        val metadata = meta ?: mutableMapOf()
        metadata["package"] = caller

        scope.launch {
            createLog(
                level = level,
                type = type,
                action = action,
                createdDate = now,
                description = description,
                message = message,
                initiatorId = initiatorId,
                initiatorIp = initiatorIp,
                targetId = targetId,
                meta = meta,
            )
        }
    }

    var loggedException = false
    fun createLog(
        level: LogLevel,
        type: LogType,
        action: UserAction,
        createdDate: Long,
        description: String,
        message: String,
        initiatorId: Ulid? = null,
        initiatorIp: String? = null,
        targetId: Ulid? = null,
        meta: Map<String, String>?,
    ): Boolean {
        fun printLog(error: Boolean) {
            val s = StringBuilder()
            fun StringBuilder.div() = s.append("  -  ")

            s.append("\n********************************************************\n")
            if (error) {
                s.append("FAILED TO SAVE LOG TO DATABASE")
            }
            s.appendLine().append(level).div().append(type).div().append(action).appendLine()
            s.append("At ").append(Instant.ofEpochSecond(createdDate)).div().append("Initiated by ID: ").append(initiatorId).div()

            if (!meta.isNullOrEmpty()) {
                s.appendLine()
                meta.forEach {
                    s.append(it.key).append(": ").append(it.value).div()
                }
                s.appendLine()
            }

            s.append("Initiator IP: ").append(initiatorIp).div()
            s.append("Target ID: ").append(targetId).appendLine().append(description).appendLine().append(message).appendLine()


            println(s.toString())
        }

        try {
            logRepository.insertLog(
                level = level.ordinal,
                type = type.ordinal,
                action = action.ordinal,
                createdDate = createdDate,
                description = description,
                message = message,
                initiatorId = initiatorId?.toString(),
                initiatorIp = initiatorIp,
                targetId = targetId?.toString(),
                meta = meta?.let { Json.encodeToString(it) },
            )

        } catch (e: Exception) {
            if (!loggedException) {
                println("===\nFAILED TO INSERT LOG TO DATABASE\n===")
                e.printStackTrace()
                loggedException = true
            }
            printLog(true)
            return false
        }

        if (State.App.printLogs || State.App.isDev) {
            printLog(false)
        }
        return true
    }

    fun getLogs(meta: RequestMeta, log: LogMeta): Result<List<Log>> {
        try {
            return logRepository.getPage(
                page = log.page,
                amount = log.amount,
                searchText = log.searchText,
                userId = log.userId,
                targetId = log.targetId,
                ip = log.ip,
                severityList = log.severityList.takeIf { it.isNotEmpty() },
                logTypeList = log.logTypeList.takeIf { it.isNotEmpty() },
                fromDate = log.fromDate,
                toDate = log.toDate,
            ).toResult()
        } catch (e: Exception) {
            this.error(
                type = LogType.SYSTEM,
                action = meta.action,
                description = "Failed to get logs from database.",
                message = e.stackTraceToString(),
                initiatorIp = meta.ip,
                initiatorId = meta.targetId
            )
            return Result.error("Failed to get logs from database.")
        }
    }
}