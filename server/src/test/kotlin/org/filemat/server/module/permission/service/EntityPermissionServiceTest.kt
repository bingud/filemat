package org.filemat.server.module.permission.service

import com.github.f4b6a3.ulid.UlidCreator
import io.mockk.every
import io.mockk.mockk
import io.mockk.junit5.MockKExtension
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.unixNow
import org.filemat.server.config.Props
import org.filemat.server.module.file.model.FilesystemEntity
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.EntityPermissionDto
import org.filemat.server.module.permission.model.Permission
import org.filemat.server.module.permission.model.PermissionType
import org.filemat.server.module.permission.repository.PermissionRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue


@ExtendWith(MockKExtension::class)
class EntityPermissionServiceTest {

    private val permissionRepository = mockk<PermissionRepository>()
    private val logService = mockk<LogService>()
    private val entityService = mockk<EntityService>()

    private lateinit var entityPermissionService: EntityPermissionService

    private val entityIdA = UlidCreator.getUlid()
    private val entityIdB = UlidCreator.getUlid()
    private val roleId = Props.userRoleId
    private val userId = UlidCreator.getUlid()
    private val pathA = "/home/wsl/test"
    private val pathB = "/"
    private val pathInherited = "/random/folder"
    private val inode = 123123L

    @BeforeEach
    fun setup() {
        entityPermissionService = EntityPermissionService(
            permissionRepository = permissionRepository,
            logService = logService,
            entityService = entityService
        )

        // permission
        every { permissionRepository.getAll() } returns listOf(
            EntityPermissionDto(
                permissionId = UlidCreator.getUlid(),
                permissionType = PermissionType.USER,
                entityId = entityIdA,
                userId = userId,
                roleId = null,
                permissions = "[]",
                createdDate = unixNow()
            ),
            EntityPermissionDto(
                permissionId = UlidCreator.getUlid(),
                permissionType = PermissionType.USER,
                entityId = entityIdB,
                userId = userId,
                roleId = null,
                permissions = "[0]",
                createdDate = unixNow()
            )
        )

        // entity
        every { entityService.getById(entityIdA, any()) } returns Result.ok(
            FilesystemEntity(
                entityId = entityIdA,
                path = pathA,
                inode = inode,
                isFilesystemSupported = true,
                ownerId = userId
            ),
        )
        every { entityService.getById(entityIdB, any()) } returns Result.ok(
            FilesystemEntity(
                entityId = entityIdB,
                path = pathB,
                inode = inode,
                isFilesystemSupported = true,
                ownerId = userId
            )
        )

        every { logService.error(any(), any(), any(), any()) } returns Unit

        entityPermissionService.loadPermissionsFromDatabase()

    }



    @Test
    fun `EntityPermissionService should return no permission`() {
        val result = entityPermissionService.getUserPermission(
            filePath = pathA,
            isNormalized = true,
            userId = userId,
            roles = emptyList()
        )

        assertTrue {
            result?.permissions?.size == 0
        }
    }

    @Test
    fun `EntityPermissionService should return permission`() {
        val result = entityPermissionService.getUserPermission(
            filePath = pathB,
            isNormalized = true,
            userId = userId,
            roles = emptyList()
        )

        assertTrue {
            result != null && result.permissions.contains(Permission.READ)
        }
    }

    @Test
    fun `EntityPermissionService should return inherited permission`() {
        val result = entityPermissionService.getUserPermission(
            filePath = pathB,
            isNormalized = true,
            userId = userId,
            roles = emptyList()
        )
        assertNotNull(result, "permission A must not be null")

        val inherited = entityPermissionService.getUserPermission(
            filePath = pathInherited,
            isNormalized = true,
            userId = userId,
            roles = emptyList()
        )
        assertNotNull(inherited, "permission B must not be null")

        assertTrue {
            result.permissionId == inherited.permissionId &&
            result.permissions.size == inherited.permissions.size
        }
    }
}