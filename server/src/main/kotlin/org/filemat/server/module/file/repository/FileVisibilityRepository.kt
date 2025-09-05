package org.filemat.server.module.file.repository

import org.filemat.server.module.file.model.FileVisibility
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface FileVisibilityRepository : CrudRepository<FileVisibility, String> {

    @Query("SELECT * FROM folder_visibility")
    fun getAll(): List<FileVisibility>

    @Modifying
    @Query("INSERT OR REPLACE INTO folder_visibility (path, is_exposed, created_date) VALUES (:path, :isExposed, :now)")
    fun insertOrReplace(path: String, isExposed: Boolean, now: Long): Int

    @Modifying
    @Query("DELETE FROM folder_visibility WHERE path = :path")
    fun remove(path: String): Int
}