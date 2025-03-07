package org.filemat.server.module.file.repository

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.module.file.model.FilesystemEntity
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface EntityRepository : CrudRepository<FilesystemEntity, Ulid> {

    @Query("SELECT * FROM files WHERE entity_id = :entityId")
    fun getById(entityId: Ulid): FilesystemEntity?

    @Query("SELECT * FROM files WHERE path = :path")
    fun getByPath(path: String): FilesystemEntity?

    @Modifying
    @Query("UPDATE files SET path = :path, inode = :inode WHERE entity_id = :entityId")
    fun updateInodeAndPath(entityId: Ulid, inode: Long?, path: String?): Int
}