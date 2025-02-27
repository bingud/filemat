package org.filemat.server.module.file.repository

import org.filemat.server.module.file.model.FolderVisibility
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface FolderVisibilityRepository : CrudRepository<FolderVisibility, String> {

    @Query("SELECT * FROM folder_visibility")
    fun getAll(): List<FolderVisibility>

}