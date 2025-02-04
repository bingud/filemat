package org.filemat.server.module.log.repository

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.module.log.model.Log
import org.filemat.server.module.log.model.LogLevel
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.user.model.UserAction
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface LogRepository : CrudRepository<Log, Int> {

    @Modifying
    @Query("INSERT INTO log " +
            "(level, type, action, created_date, description, message, initiator_user_id, initiator_ip, target_id)" +
            "VALUES (:level, :type, :action, :createdDate, :description, :message, :initiatorId, :initiatorIp, :targetId)")
    fun saveLog(
        level: Int,
        type: Int,
        action: Int,
        createdDate: Long,
        description: String,
        message: String,
        initiatorId: String?,
        initiatorIp: String?,
        targetId: String?,
    ): Int
}