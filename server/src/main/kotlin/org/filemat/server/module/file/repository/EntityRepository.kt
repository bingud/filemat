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

    @Query("SELECT * FROM files WHERE path_key = :prefixKey OR path_key LIKE CONCAT(:prefixKey, '/%')")
    fun getAllByPathPrefix(prefixKey: String): List<FilesystemEntity>

    @Query("SELECT * FROM files WHERE inode = :inode")
    fun getByInode(inode: Long): FilesystemEntity?

    @Query("SELECT * FROM files WHERE file_key = :fileKey")
    fun getByFileKey(fileKey: String): FilesystemEntity?

    @Query("SELECT * FROM files WHERE owner_user_id = :userId")
    fun getByOwnerUserId(userId: Ulid): List<FilesystemEntity>

    @Modifying
    @Query("UPDATE files SET path = :path, path_key = :pathKey, inode = :inode, file_key = :fileKey, is_filesystem_supported = :isFilesystemSupported WHERE entity_id = :entityId")
    fun updateIdentityAndPath(entityId: Ulid, path: String?, pathKey: String?, inode: Long?, fileKey: String?, isFilesystemSupported: Boolean): Int

    @Modifying
    @Query("UPDATE files SET path = :path, path_key = :pathKey WHERE entity_id = :entityId")
    fun updatePath(entityId: Ulid, path: String?, pathKey: String?): Int

    @Modifying
    @Query("UPDATE files SET inode = :inode WHERE entity_id = :entityId")
    fun updateInode(entityId: Ulid, inode: Long?): Int

    @Modifying
    @Query("INSERT INTO files (entity_id, path, path_key, inode, file_key, is_filesystem_supported, owner_user_id) VALUES (:entityId, :path, :pathKey, :inode, :fileKey, :isFilesystemSupported, :ownerId)")
    fun insert(
        entityId: Ulid,
        path: String?,
        pathKey: String?,
        inode: Long?,
        fileKey: String?,
        isFilesystemSupported: Boolean,
        ownerId: Ulid?,
    ): Int
}