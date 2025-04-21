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

    @Query("SELECT * FROM files WHERE path = :path")
    fun getByPath(path: String): FilesystemEntity?

    @Query("SELECT * FROM files WHERE inode = :inode AND path IS NULL")
    fun getByInodeWithNullPath(inode: Long): FilesystemEntity?

    @Modifying
    @Query("UPDATE files SET path = :path, inode = :inode WHERE entity_id = :entityId")
    fun updateInodeAndPath(entityId: Ulid, inode: Long?, path: String?): Int

    @Modifying
    @Query("UPDATE files SET path = :path WHERE entity_id = :entityId")
    fun updatePath(entityId: Ulid, path: String?): Int

    @Modifying
    @Query("UPDATE files SET inode = :inode WHERE entity_id = :entityId")
    fun updateInode(entityId: Ulid, inode: Long?): Int

    @Modifying
    @Query("INSERT INTO files (entity_id, path, inode, is_filesystem_supported, owner_user_id, follow_symlinks) VALUES (:entityId, :path, :inode, :isFilesystemSupported, :ownerId, :followSymlinks)")
    fun insert(
        entityId: Ulid,
        path: String?,
        inode: Long?,
        isFilesystemSupported: Boolean,
        ownerId: Ulid,
        followSymlinks: Boolean
    ): Int
}