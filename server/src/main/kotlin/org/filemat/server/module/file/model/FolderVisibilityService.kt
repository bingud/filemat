package org.filemat.server.module.file.model

import org.filemat.server.module.file.repository.FolderVisibilityRepository
import org.springframework.stereotype.Service

@Service
class FolderVisibilityService(
    private val folderVisibilityRepository: FolderVisibilityRepository,
) {



}