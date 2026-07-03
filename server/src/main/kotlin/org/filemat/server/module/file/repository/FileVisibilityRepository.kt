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
    @Query("INSERT OR REPLACE INTO folder_visibility (path, path_key, is_exposed, created_date) VALUES (:path, :pathKey, :isExposed, :now)")
    fun insertOrReplace(path: String, pathKey: String, isExposed: Boolean, now: Long): Int

    @Modifying
    @Query("DELETE FROM folder_visibility WHERE path_key = :pathKey")
    fun remove(pathKey: String): Int
}