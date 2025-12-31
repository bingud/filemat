package org.filemat.server.module.sharedFile.repository

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.module.savedFile.SavedFile
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface SavedFileRepository : CrudRepository<SavedFile, Ulid> {
    @Query("SELECT EXISTS(SELECT 1 FROM saved_files WHERE user_id = :userId && path = :path)")
    fun exists(userId: Ulid, path: String): Boolean

    @Modifying
    @Query("INSERT INTO saved_files (user_id, path, created_date) VALUES (:userId, :path, :createdDate)")
    fun create(userId: Ulid, path: String, createdDate: Long)

    @Modifying
    @Query("DELETE FROM saved_files WHERE path = :path")
    fun remove(path: String): Int

    @Modifying
    @Query("DELETE FROM saved_files WHERE user_id = :userId AND path = :path")
    fun removeByUserId(userId: Ulid, path: String): Int

    @Query("SELECT * FROM saved_files WHERE user_id = :userId")
    fun getAll(userId: Ulid): List<SavedFile>

    @Modifying
    @Query(
        """
        UPDATE saved_files 
        SET path = CONCAT(:newPath, SUBSTRING(path, LENGTH(:path) + 1)) 
        WHERE path = :path OR path LIKE CONCAT(:path, '/%')
    """
    )
    fun updatePath(path: String, newPath: String): Int
}