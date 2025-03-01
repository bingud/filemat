package org.filemat.server.module.file.repository

import org.filemat.server.common.util.unixNow
import org.filemat.server.module.file.model.FolderVisibility
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface FolderVisibilityRepository : CrudRepository<FolderVisibility, String> {

    @Query("SELECT * FROM folder_visibility")
    fun getAll(): List<FolderVisibility>

    @Modifying
    @Query("INSERT OR REPLACE INTO folder_visibility (path, is_exposed, created_date) VALUES (:path, :isExposed, :now)")
    fun insertOrReplace(path: String, isExposed: Boolean, now: Long): Int

}