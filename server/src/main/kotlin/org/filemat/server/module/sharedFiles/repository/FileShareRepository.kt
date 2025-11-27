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

    @Query("SELECT * FROM shared_files WHERE user_id = :userId")
    fun getSharesByUserId(userId: Ulid): List<FileShare>

    @Query("SELECT * FROM shared_files WHERE share_id = :shareId")
    fun getSharesByShareId(shareId: String): FileShare?

    @Modifying
    @Query("""        
        INSERT INTO shared_files 
        (share_id, file_id, user_id, created_date, max_age, is_password, password) 
        VALUES (:shareId, :fileId, :userId, :createdDate, :maxAge, :isPassword, :password)
    """)
    fun insert(shareId: String, fileId: Ulid, userId: Ulid, createdDate: Long, maxAge: Long, isPassword: Boolean, password: String?)

    @Modifying
    @Query("DELETE FROM shared_files WHERE max_age > 0 AND created_date + max_age < :now")
    fun deleteExpired(now: Long): Int

    @Modifying
    @Query("DELETE FROM shared_files WHERE share_id = :shareId AND file_id = :entityId")
    fun delete(shareId: String, entityId: Ulid): Int

    @Query("SELECT * FROM shared_files WHERE share_id = :shareId AND file_id = :fileId")
    fun getShareByShareIdAndFileId(shareId: String, fileId: Ulid): FileShare?

    @Query("SELECT is_password FROM shared_files WHERE share_id = :shareId")
    fun getPasswordStatus(shareId: String): Int?
}