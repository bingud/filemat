package org.filemat.server.module.sharedFile.repository

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.module.savedFile.SavedFile
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface SavedFileRepository : CrudRepository<SavedFile, Ulid> {
    @Query("SELECT EXISTS(SELECT 1 FROM saved_files WHERE user_id = :userId AND path_key = :pathKey)")
    fun exists(userId: Ulid, pathKey: String): Boolean

    @Modifying
    @Query("INSERT INTO saved_files (user_id, path, path_key, created_date) VALUES (:userId, :path, :pathKey, :createdDate)")
    fun create(userId: Ulid, path: String, pathKey: String, createdDate: Long)

    @Modifying
    @Query("DELETE FROM saved_files WHERE path_key = :pathKey")
    fun remove(pathKey: String): Int

    @Modifying
    @Query("DELETE FROM saved_files WHERE user_id = :userId AND path_key = :pathKey")
    fun removeByUserId(userId: Ulid, pathKey: String): Int

    @Query("SELECT * FROM saved_files WHERE user_id = :userId")
    fun getAll(userId: Ulid): List<SavedFile>

    @Modifying
    @Query(
        """
        UPDATE saved_files
        SET path = CONCAT(:newPath, SUBSTRING(path, LENGTH(:path) + 1)),
            path_key = CONCAT(:newPathKey, SUBSTRING(path_key, LENGTH(:pathKey) + 1))
        WHERE path_key = :pathKey OR path_key LIKE CONCAT(:pathKey, '/%')
    """
    )
    fun updatePath(path: String, pathKey: String, newPath: String, newPathKey: String): Int
}