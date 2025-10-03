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


class FileServiceTest {
    private val fileVisibilityService = mockk<FileVisibilityService>()
    private val entityPermissionService = mockk<EntityPermissionService>()
    private val entityService = mockk<EntityService>()
    private val logService = mockk<LogService>()
    private val filesystemService = mockk<FilesystemService>()

    val service = spyk(
        FileService(
            fileVisibilityService = fileVisibilityService,
            entityPermissionService = entityPermissionService,
            entityService = entityService,
            logService = logService,
            filesystem = filesystemService,
        )
    )

    @Test
    fun `getFolderEntries returns permission denied when user not allowed`() {
        every { fileVisibilityService.isPathAllowed(any()) } returns null
        every { service.verifyEntityInode(any(), any()) } returns Result.ok()

        val roleId = Ulid.fast()
        State.Auth.roleMap[roleId] = Role(
            roleId = roleId,
            name = "",
            createdDate = 0,
            permissions = listOf()
        )

        val user = Principal(
            userId = Ulid.fast(),
            email = "",
            username = "",
            mfaTotpStatus = false,
            isBanned = false,
            roles = mutableListOf(roleId)
        )
        val path = FilePath.of("/home")

        every { service.hasFilePermission(path, user, any(), any()) } returns service.hasFilePermission(path, user, false, FilePermission.READ)
        val result = service.getFolderEntries(
            user = user, canonicalPath = path
        )
    }
}