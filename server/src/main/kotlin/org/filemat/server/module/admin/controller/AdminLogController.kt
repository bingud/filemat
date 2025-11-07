package org.filemat.server.module.admin.controller

import jakarta.servlet.http.HttpServletRequest
import org.filemat.server.common.model.handle
import org.filemat.server.common.util.*
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.dto.RequestMeta
import org.filemat.server.config.auth.Authenticated
import org.filemat.server.module.admin.model.LogMeta
import org.filemat.server.module.log.model.LogLevel
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.model.serializeList
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.user.model.UserAction
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Authenticated([SystemPermission.MANAGE_SYSTEM])
@RestController
@RequestMapping("/v1/admin/log")
class AdminLogController(
    private val logService: LogService
) : AController() {

    @PostMapping("/get")
    fun adminGetLogsMapping(
        request: HttpServletRequest,
        @RequestParam("page") page: String,
        @RequestParam("amount", required = false) amount: String?,
        @RequestParam("search-text", required = false) searchText: String?,
        @RequestParam("initiator-id", required = false) rawInitiatorId: String?,
        @RequestParam("target-id", required = false) rawTargetId: String?,
        @RequestParam("ip", required = false) rawIp: String?,
        @RequestParam("severity-list") rawSeverityList: String,
        @RequestParam("log-type-list") rawLogTypeList: String,
        @RequestParam("from-date", required = false) rawFromDate: String?,
        @RequestParam("to-date", required = false) rawToDate: String?,
    ): ResponseEntity<String> {
        val principal = request.getPrincipal()!!
        val meta = RequestMeta(
            initiatorId = principal.userId,
            ip = request.realIp(),
            action = UserAction.GET_LOGS,
            principal = principal
        )

        val logMeta = LogMeta(
            page = page.toIntOrNull() ?: return bad("Invalid page."),
            amount = amount?.toIntOrNull() ?: 50,
            searchText = searchText,
            userId = parseUlidOrNull(rawInitiatorId),
            targetId = parseUlidOrNull(rawTargetId),
            ip = rawIp,
            severityList = parseEnumList<LogLevel>(rawSeverityList),
            logTypeList = parseEnumList<LogType>(rawLogTypeList),
            fromDate = rawFromDate?.toLongOrNull(),
            toDate = rawToDate?.toLongOrNull()
        )

        val result = logService.getLogs(meta, logMeta).handle {
            if (it.hasError) return internal(it.error)
        }
        val serialized = result.serializeList()

        return ok(serialized)
    }
}