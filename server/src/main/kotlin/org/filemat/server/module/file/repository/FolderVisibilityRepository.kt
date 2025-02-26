package org.filemat.server.module.file.repository

import org.filemat.server.module.file.model.FolderVisibility
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface FolderVisibilityRepository : CrudRepository<FolderVisibility, String> {



}