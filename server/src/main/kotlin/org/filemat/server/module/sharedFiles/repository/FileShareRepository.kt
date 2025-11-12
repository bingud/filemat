package org.filemat.server.module.sharedFiles.repository

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.module.sharedFiles.model.FileShare
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface FileShareRepository : CrudRepository<FileShare, Ulid> {

    @Query("SELECT * FROM shared_files WHERE file_id = :fileId")
    fun getSharesByFileId(fileId: Ulid): List<FileShare>

}