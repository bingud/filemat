package org.filemat.server.module.sharedFiles.repository

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.module.sharedFiles.model.FileShare
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface FileShareRepository : CrudRepository<FileShare, Ulid> {

    @Query("SELECT * FROM shared_files WHERE file_id = :fileId")
    fun getSharesByFileId(fileId: Ulid): List<FileShare>

    @Query("SELECT * FROM shared_files WHERE share_id = :shareId")
    fun getSharesByShareId(shareId: String): FileShare?

    @Modifying
    @Query("""        
        INSERT INTO shared_files 
        (share_id, file_id, user_id, created_date, max_age, is_password, password) 
        VALUES (:shareId, :fileId, :userId, :createdDate, :maxAge, :isPassword, :password)
    """)
    fun insert(shareId: String, fileId: Ulid, userId: Ulid, createdDate: Long, maxAge: Long, isPassword: Boolean, password: String?)

}
