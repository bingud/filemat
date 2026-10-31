package org.filemat.server.module.file.repository

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.module.file.model.FilesystemEntity
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface EntityRepository : CrudRepository<FilesystemEntity, Ulid> {
    @Modifying
    @Query("DELETE FROM files WHERE entity_id = :entityId")
    fun delete(entityId: Ulid): Int

    @Query("SELECT * FROM files WHERE entity_id = :entityId")
    fun getById(entityId: Ulid): FilesystemEntity?

    @Query("SELECT * FROM files WHERE path_key = :pathKey")
    fun getByPathKey(pathKey: String): FilesystemEntity?

    // Exact prefix comparison of path keys. LIKE would treat `_` and `%` in paths as wildcards and ignore ASCII case.
    // RTRIM keeps a root key (`/`, `C:/`) from becoming `//` or `C://`.
    @Query("SELECT * FROM files WHERE path_key = :prefixKey OR SUBSTRING(path_key, 1, LENGTH(RTRIM(:prefixKey, '/')) + 1) = CONCAT(RTRIM(:prefixKey, '/'), '/')")
    fun getAllByPathPrefix(prefixKey: String): List<FilesystemEntity>

    @Query("SELECT * FROM files WHERE file_key = :fileKey")
    fun getByFileKey(fileKey: String): FilesystemEntity?

    @Query("SELECT * FROM files WHERE owner_user_id = :userId")
    fun getByOwnerUserId(userId: Ulid): List<FilesystemEntity>

    @Modifying
    @Query("UPDATE files SET path = :path, path_key = :pathKey WHERE entity_id = :entityId")
    fun updatePath(entityId: Ulid, path: String?, pathKey: String?): Int

    @Modifying
    @Query("UPDATE files SET file_key = :fileKey, is_filesystem_supported = :isFilesystemSupported WHERE entity_id = :entityId")
    fun updateFileKey(entityId: Ulid, fileKey: String?, isFilesystemSupported: Boolean): Int

    @Modifying
    @Query("UPDATE files SET owner_user_id = :ownerId WHERE entity_id = :entityId")
    fun updateOwner(entityId: Ulid, ownerId: Ulid?): Int

    @Modifying
    @Query("INSERT INTO files (entity_id, path, path_key, file_key, is_filesystem_supported, owner_user_id) VALUES (:entityId, :path, :pathKey, :fileKey, :isFilesystemSupported, :ownerId)")
    fun insert(
        entityId: Ulid,
        path: String?,
        pathKey: String?,
        fileKey: String?,
        isFilesystemSupported: Boolean,
        ownerId: Ulid?,
    ): Int
}