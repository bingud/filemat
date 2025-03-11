package org.filemat.server.module.file.service

import com.github.f4b6a3.ulid.UlidCreator
import io.mockk.every
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.spyk
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.unixNow
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FileMetadata
import org.filemat.server.module.file.model.FileType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.EntityPermission
import org.filemat.server.module.permission.model.Permission
import org.filemat.server.module.permission.model.PermissionType
import org.filemat.server.module.permission.service.EntityPermissionService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.assertTrue


@ExtendWith(MockKExtension::class)
class FileServiceTest {

    private val folderVisibilityService = mockk<FolderVisibilityService>()
    private val entityPermissionService = mockk<EntityPermissionService>()
    private val entityService = mockk<EntityService>()
    private val logService = mockk<LogService>()

    val fileService = FileService(
        folderVisibilityService = folderVisibilityService,
        entityPermissionService = entityPermissionService,
        entityService = entityService,
        logService = logService,
    )

    val path = "/home/wsl/test"
    val userId = UlidCreator.getUlid()
    private val principal = Principal(
        userId = userId,
        email = "",
        username = "",
        mfaTotpStatus = false,
        isBanned = false,
        roles = mutableListOf(Props.userRoleId)
    )


    @Test
    fun `  a   `() {

    }


}