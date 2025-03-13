package org.filemat.server.module.file.service

import com.github.f4b6a3.ulid.Ulid
import io.mockk.every
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.getUlid
import org.filemat.server.common.util.unixNow
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.model.FilesystemEntity
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.Permission
import org.filemat.server.module.permission.service.EntityPermissionService
import org.filemat.server.module.role.model.Role
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.io.File
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue


@ExtendWith(MockKExtension::class)
class FileServiceTest {

    private val folderVisibilityService = mockk<FolderVisibilityService>()
    private val entityPermissionService = mockk<EntityPermissionService>()
    private val entityService = mockk<EntityService>()
    private val logService = mockk<LogService>()
    private val filesystemService = mockk<FilesystemService>()

    val fileService = FileService(
        folderVisibilityService = folderVisibilityService,
        entityPermissionService = entityPermissionService,
        entityService = entityService,
        logService = logService,
        filesystem = filesystemService,
    )

    val path = "/home/wsl/test"
    val userId = getUlid()
    val rolesUser = mutableListOf(Props.userRoleId)
    val rolesAdmin = mutableListOf(Props.adminRoleId)
    val inode = 12345L
    val entityId = getUlid()
    val fileTime = FileTime.from(unixNow(), TimeUnit.SECONDS)

    fun setup() {
        every { folderVisibilityService.isPathAllowed(any(), any()) } returns null
        every { entityService.getByPath(any(), any()) } returns Result.ok(FilesystemEntity(
            entityId = entityId,
            path = path,
            inode = inode,
            isFilesystemSupported = true,
            ownerId = userId
        ))
        every { filesystemService.exists(any(), any()) } returns true
        every { filesystemService.getInode(any()) } returns inode

        every { filesystemService.listFiles(any()) } returns listOf(File("$path/example.txt"))
        every { filesystemService.readAttributes(any(), any()) } returns CustomFileAttributes(
            creationTime = fileTime,
            lastAccessTime = fileTime,
            lastModifiedTime = fileTime,
            isDirectory = false,
            isRegularFile = true,
            isSymbolicLink = false,
            size = 123
        )
        every { entityPermissionService.getUserPermission(any(), any(), any(), any()) } returns null

        State.Auth.roleMap[Props.adminRoleId] = Role(
            roleId = Props.adminRoleId,
            name = "admin",
            createdDate = unixNow(),
            permissions = listOf(Permission.ACCESS_ALL_FILES)
        )
        State.Auth.roleMap[Props.userRoleId] = Role(
            roleId = Props.userRoleId,
            name = "user",
            createdDate = unixNow(),
            permissions = listOf()
        )
    }

    @Test
    fun `getFolderEntries should return entries`() {
        setup()

        val principal = getPrincipal(rolesAdmin)
        val result = fileService.getFolderEntries(path, principal)

        assertTrue {
            result.isSuccessful
        }
    }

    @Test
    fun `getFolderEntries should return rejected`() {
        setup()

        val principal = getPrincipal(rolesUser)
        val result = fileService.getFolderEntries(path, principal)

        assertTrue {
            result.rejected
        }
    }


    fun getPrincipal(roles: MutableList<Ulid>): Principal {
        return Principal(
            userId = userId,
            email = "",
            username = "",
            mfaTotpStatus = false,
            isBanned = false,
            roles = roles
        )
    }

    class CustomFileAttributes(
        private val creationTime: FileTime,
        private val lastAccessTime: FileTime,
        private val lastModifiedTime: FileTime,
        private val isDirectory: Boolean,
        private val isRegularFile: Boolean,
        private val isSymbolicLink: Boolean,
        private val size: Long
    ) : BasicFileAttributes {

        override fun creationTime(): FileTime = creationTime
        override fun lastAccessTime(): FileTime = lastAccessTime
        override fun lastModifiedTime(): FileTime = lastModifiedTime
        override fun isDirectory(): Boolean = isDirectory
        override fun isRegularFile(): Boolean = isRegularFile
        override fun isSymbolicLink(): Boolean = isSymbolicLink
        override fun isOther(): Boolean = !(isDirectory || isRegularFile || isSymbolicLink)
        override fun size(): Long = size
        override fun fileKey(): Any? = null // Optional, can be null
    }

}