package org.filemat.server.module.log.repository

import org.filemat.server.module.log.model.Log
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface LogRepository : CrudRepository<Log, Int> {

    @Modifying
    @Query("INSERT INTO log " +
            "(level, type, action, created_date, description, message, initiator_user_id, initiator_ip, target_id, metadata)" +
            "VALUES (:level, :type, :action, :createdDate, :description, :message, :initiatorId, :initiatorIp, :targetId, :meta)")
    fun insertLog(
        level: Int,
        type: Int,
        action: Int,
        createdDate: Long,
        description: String,
        message: String,
        initiatorId: String?,
        initiatorIp: String?,
        targetId: String?,
        meta: String?,
    ): Int
}