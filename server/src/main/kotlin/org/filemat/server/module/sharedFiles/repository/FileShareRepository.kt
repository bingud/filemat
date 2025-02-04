package org.filemat.server.module.sharedFiles.repository

import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.module.sharedFiles.model.FileShare
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface FileShareRepository : CrudRepository<FileShare, Ulid> {



}