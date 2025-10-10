package org.filemat.server.module.file.service

import com.github.f4b6a3.ulid.Ulid
import io.mockk.every
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.service.EntityPermissionService
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.permission.model.FilePermission
import org.filemat.server.module.role.model.Role
import org.junit.jupiter.api.Test


//class FileServiceTest {
//    private val fileVisibilityService = mockk<FileVisibilityService>()
//    private val entityPermissionService = mockk<EntityPermissionService>()
//    private val entityService = mockk<EntityService>()
//    private val logService = mockk<LogService>()
//    private val filesystemService = mockk<FilesystemService>()
//
//    val service = spyk(
//        FileService(
//            fileVisibilityService = fileVisibilityService,
//            entityPermissionService = entityPermissionService,
//            entityService = entityService,
//            logService = logService,
//            filesystem = filesystemService,
//        )
//    )
//}