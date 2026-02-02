package org.filemat.server.module.log.repository

import com.github.f4b6a3.ulid.Ulid
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

    @Query(
    """
    SELECT *
    FROM log
    WHERE
      ( :searchText IS NULL OR :searchText = '' OR
        LOWER(description) LIKE '%' || LOWER(:searchText) || '%' OR
        LOWER(message) LIKE '%' || LOWER(:searchText) || '%' OR
        LOWER(metadata) LIKE '%' || LOWER(:searchText) || '%'
      )
      AND ( :userId IS NULL OR initiator_user_id = :userId )
      AND ( :targetId IS NULL OR target_id = :targetId )
      AND ( :ip IS NULL OR initiator_ip = :ip )
      AND ( :#{#severityList == null or #severityList.size() == 0} = true OR level IN (:severityList) )
      AND ( :#{#logTypeList == null or #logTypeList.size() == 0} = true OR type IN (:logTypeList) )
      AND ( :fromDate IS NULL OR created_date >= :fromDate )
      AND ( :toDate IS NULL OR created_date <= :toDate )
    ORDER BY created_date DESC
    LIMIT :amount OFFSET :#{#page * #amount}
    """
    )
    fun getPage(
        page: Int,
        amount: Int,
        searchText: String?,  // now nullable
        userId: Ulid?,
        targetId: Ulid?,
        ip: String?,
        severityList: List<Int>?,
        logTypeList: List<Int>?,
        fromDate: Long?,
        toDate: Long?,
    ): List<Log>
}