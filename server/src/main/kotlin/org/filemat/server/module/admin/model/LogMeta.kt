package org.filemat.server.module.admin.model

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.module.log.model.LogLevel
import org.filemat.server.module.log.model.LogType


data class LogMeta(
    val page: Int,
    val amount: Int,
    val searchText: String?,
    val userId: Ulid?,
    val targetId: Ulid?,
    val ip: String?,
    val severityList: List<LogLevel>,
    val logTypeList: List<LogType>,
    val fromDate: Long?,
    val toDate: Long?,
)