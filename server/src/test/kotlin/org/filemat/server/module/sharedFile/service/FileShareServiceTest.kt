package org.filemat.server.module.sharedFile.service

import com.github.f4b6a3.ulid.Ulid
import com.github.f4b6a3.ulid.UlidCreator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.dto.ArgonHash
import org.filemat.server.common.util.unixNow
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.FilesystemEntity
import org.filemat.server.module.file.service.EntityService
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.SystemPermission
import org.filemat.server.module.sharedFile.model.FileShare
import org.filemat.server.module.sharedFile.model.isExpired
import org.filemat.server.module.sharedFile.repository.FileShareRepository
import org.filemat.server.module.user.model.UserAction
import org.filemat.server.testutil.assertError
import org.filemat.server.testutil.assertNotFound
import org.filemat.server.testutil.assertRejected
import org.filemat.server.testutil.assertSuccess
import org.filemat.server.testutil.principalWith
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.security.crypto.password.PasswordEncoder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests public file shares: who may create and remove them, when a link stops working,
 * and how password-protected shares hand out access tokens.
 */
class FileShareServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val state = State(env = emptyMap())
    private val repository = mockk<FileShareRepository>(relaxed = true)
    private val entityService = mockk<EntityService>()
    private val fileService = mockk<FileService>()
    private val passwordEncoder = mockk<PasswordEncoder>()

    private val service = FileShareService(
        fileShareRepository = repository,
        logService = mockk<LogService>(relaxed = true),
        entityService = entityService,
        fileService = fileService,
        passwordEncoder = passwordEncoder,
        state = state,
    )

    init {
        every { repository.getSharesByShareId(any()) } returns null
        every { repository.getShareByShareIdAndFileId(any(), any()) } returns null
    }

    private val entityId = UlidCreator.getUlid()
    private val ownerId = UlidCreator.getUlid()
    private val ip = "share-ip-${UlidCreator.getUlid()}"

    private fun share(
        id: String = "my-link",
        userId: Ulid = ownerId,
        password: String? = null,
        createdDate: Long = unixNow(),
        maxAge: Long = 0,
    ) = FileShare(id, entityId, userId, createdDate, maxAge, isPassword = password != null, password = password)

    private fun stored(share: FileShare) {
        every { repository.getSharesByShareId(share.shareId) } returns share
        every { repository.getShareByShareIdAndFileId(share.shareId, share.fileId) } returns share
        every { repository.getSharesByFileId(share.fileId) } returns listOf(share)
    }

    private fun entity(path: String) = FilesystemEntity(entityId, path, 1, true, ownerId)

    @Nested
    inner class Expiry {

        @Test
        fun `share without max age never expires`() {
            assertFalse(share(createdDate = 0, maxAge = 0).isExpired())
        }

        @Test
        fun `share expires once its max age has passed`() {
            val now = 10_000L

            assertFalse(share(createdDate = now - 100, maxAge = 100).isExpired(now))
            assertTrue(share(createdDate = now - 101, maxAge = 100).isExpired(now))
        }

        @Test
        fun `expired share cannot be opened`() {
            stored(share(createdDate = unixNow() - 1000, maxAge = 10))

            assertNotFound(service.getSharesByShareId("my-link", UserAction.GET_SHARED_FILE))
            assertRejected(service.getByShareToken("my-link", UserAction.GET_SHARED_FILE))
        }

        @Test
        fun `expired shares are left out of listings`() {
            val live = share(id = "live")
            val dead = share(id = "dead", createdDate = unixNow() - 1000, maxAge = 10)
            every { repository.getSharesByFileId(entityId) } returns listOf(live, dead)
            every { repository.getSharesByUserId(ownerId) } returns listOf(live, dead)
            every { repository.findAll() } returns listOf(live, dead)

            assertEquals(listOf(live), service.getSharesByEntityId(entityId, UserAction.GET_FILE_SHARES).value)
            assertEquals(listOf(live), service.getSharesByUserId(ownerId, UserAction.GET_FILE_SHARES).value)
            assertEquals(listOf(live), service.getAllShares(UserAction.GET_FILE_SHARES).value)
        }
    }

    @Nested
    inner class OpeningAShare {

        @Test
        fun `public share is opened by its share id`() {
            val public = share()
            stored(public)

            assertEquals(public, service.getByShareToken("my-link", UserAction.GET_SHARED_FILE).value)
        }

        @Test
        fun `unknown share is rejected`() {
            every { repository.getSharesByShareId(any()) } returns null

            assertRejected(service.getByShareToken("nothing", UserAction.GET_SHARED_FILE))
        }

        @Test
        fun `password share cannot be opened by guessing its share id`() {
            stored(share(password = "hash"))

            assertRejected(service.getByShareToken("my-link", UserAction.GET_SHARED_FILE))
        }

        @Test
        fun `made-up token does not open a password share`() {
            stored(share(password = "hash"))

            assertRejected(service.getByShareToken("a-made-up-token", UserAction.GET_SHARED_FILE))
        }

        @Test
        fun `password status of a share`() {
            every { repository.getPasswordStatus("with") } returns 1
            every { repository.getPasswordStatus("without") } returns 0
            every { repository.getPasswordStatus("missing") } returns null

            assertTrue(service.getPasswordStatus("with", UserAction.GET_SHARED_FILE).value)
            assertFalse(service.getPasswordStatus("without", UserAction.GET_SHARED_FILE).value)
            assertNotFound(service.getPasswordStatus("missing", UserAction.GET_SHARED_FILE))
        }
    }

    @Nested
    inner class PasswordLogin {


        private fun matches() {
            every { passwordEncoder.matches("secret", "hash") } returns true
            every { passwordEncoder.matches(neq("secret"), any()) } returns false
        }

        private fun login(shareId: String = "my-link", password: String = "secret", ip: String = this@FileShareServiceTest.ip) =
            service.login(shareId, password, principal = null, ip = ip, userAction = UserAction.GET_SHARED_FILE)

        @Test
        fun `correct password returns a token that opens the share`() {
            val protected = share(password = "hash")
            stored(protected)
            matches()

            val token = login().value

            assertEquals(protected, service.getByShareToken(token, UserAction.GET_SHARED_FILE).value)
        }

        @Test
        fun `each login gets its own unguessable token`() {
            stored(share(password = "hash"))
            matches()

            val tokens = (1..5).map { login().value }

            assertEquals(5, tokens.toSet().size)
            tokens.forEach { assertTrue(it.length >= 32 && it != "my-link") }
        }

        @Test
        fun `wrong password is rejected`() {
            stored(share(password = "hash"))
            matches()

            assertRejected(login(password = "wrong"))
        }

        @Test
        fun `unknown share gives the same answer as a wrong password`() {
            stored(share(password = "hash"))
            matches()
            every { repository.getSharesByShareId("ghost") } returns null

            val unknown = login(shareId = "ghost", ip = "ip-a-${UlidCreator.getUlid()}")
            val wrong = login(password = "wrong", ip = "ip-b-${UlidCreator.getUlid()}")

            assertEquals(wrong.error, unknown.error)
        }

        @Test
        fun `share without a password needs no password`() {
            stored(share())

            assertEquals("my-link", login(password = "anything").value)
        }

        @Test
        fun `ninth attempt from the same address is refused even with the right password`() {
            stored(share(password = "hash"))
            matches()

            val results = (1..9).map { login(password = "wrong") }
            val blocked = login()

            assertTrue(results.take(8).all { it.error == "Incorrect password." })
            assertRejected(results.last())
            assertRejected(blocked)
            assertFalse(blocked.isSuccessful)
        }
    }

    @Nested
    inner class Creating {

        private val user = state.principalWith(userId = ownerId)
        private val file: Path get() = Files.createFile(tempDir.resolve("report.txt"))

        private fun create(
            path: Path = file,
            id: String = "my-link",
            password: ArgonHash? = null,
            maxAge: Long? = null,
        ): Result<FileShare> {
            every { fileService.isAllowedToShareFile(any(), any()) } returns Result.ok()
            every { fileService.ensureEntityIndexed(any(), any(), any(), any()) } returns Result.ok()
            every { entityService.getByPath(any(), any()) } returns Result.ok(entity(path.toString()))
            every { repository.getSharesByShareId(id) } returns null
            return service.createShare(user, FilePath.of(path.toString()), id, password, maxAge)
        }

        @Test
        fun `stores the share for the file and user`() {
            val result = create(maxAge = 3600)

            assertSuccess(result)
            verify(exactly = 1) { repository.insert("my-link", entityId, ownerId, any(), 3600, false, null) }
        }

        @Test
        fun `share without max age never expires`() {
            assertEquals(0, create(maxAge = null).value.maxAge)
        }

        @Test
        fun `password is stored hashed and marks the share protected`() {
            val result = create(password = ArgonHash("argon-hash"))

            assertTrue(result.value.isPassword)
            verify(exactly = 1) { repository.insert("my-link", entityId, ownerId, any(), 0, true, "argon-hash") }
        }

        @Test
        fun `share link already in use is rejected`() {
            val path = file
            every { repository.getSharesByShareId("my-link") } returns share()
            every { fileService.isAllowedToShareFile(any(), any()) } returns Result.ok()

            val result = service.createShare(user, FilePath.of(path.toString()), "my-link", null, null)

            assertRejected(result)
            verify(exactly = 0) { repository.insert(any(), any(), any(), any(), any(), any(), any()) }
        }

        @Test
        fun `link of an expired share can be reused`() {
            val path = file
            every { fileService.isAllowedToShareFile(any(), any()) } returns Result.ok()
            every { fileService.ensureEntityIndexed(any(), any(), any(), any()) } returns Result.ok()
            every { entityService.getByPath(any(), any()) } returns Result.ok(entity(path.toString()))
            every { repository.getSharesByShareId("my-link") } returns share(createdDate = unixNow() - 1000, maxAge = 10)

            assertSuccess(service.createShare(user, FilePath.of(path.toString()), "my-link", null, null))
        }

        @Test
        fun `user not allowed to share the file creates nothing`() {
            val path = file
            every { fileService.isAllowedToShareFile(any(), any()) } returns Result.reject("no permission")

            val result = service.createShare(user, FilePath.of(path.toString()), "my-link", null, null)

            assertRejected(result)
            verify(exactly = 0) { repository.insert(any(), any(), any(), any(), any(), any(), any()) }
        }

        @Test
        fun `missing file cannot be shared`() {
            val result = service.createShare(user, FilePath.of(tempDir.resolve("missing.txt").toString()), "my-link", null, null)

            assertFalse(result.isSuccessful)
            verify(exactly = 0) { repository.insert(any(), any(), any(), any(), any(), any(), any()) }
        }

        @Test
        fun `database failure is an error`() {
            val path = file
            every { repository.insert(any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("locked")

            assertError(create(path))
        }
    }

    @Nested
    inner class Deleting {

        private val path get() = tempDir.resolve("report.txt").toString()

        private fun setUp(share: FileShare = share(), canAccess: Boolean = true) {
            stored(share)
            every { entityService.getById(entityId, any()) } returns Result.ok(entity(path))
            every { fileService.isAllowedToAccessFile(any(), any(), any()) } returns
                if (canAccess) Result.ok() else Result.reject("no access")
        }

        @Test
        fun `creator may delete their own share`() {
            setUp()

            assertSuccess(service.deleteShare(state.principalWith(userId = ownerId), entityId, "my-link", UserAction.DELETE_FILE_SHARE))

            verify(exactly = 1) { repository.delete("my-link", entityId) }
        }

        @Test
        fun `another user may not delete it`() {
            setUp()

            assertRejected(service.deleteShare(state.principalWith(), entityId, "my-link", UserAction.DELETE_FILE_SHARE))

            verify(exactly = 0) { repository.delete(any(), any()) }
        }

        @Test
        fun `holder of manage-all-shares permission may delete any share`() {
            setUp()
            val manager = state.principalWith(SystemPermission.MANAGE_ALL_FILE_SHARES)

            assertSuccess(service.deleteShare(manager, entityId, "my-link", UserAction.DELETE_FILE_SHARE))
        }

        @Test
        fun `creator who lost access to the file may not delete`() {
            setUp(canAccess = false)

            assertRejected(service.deleteShare(state.principalWith(userId = ownerId), entityId, "my-link", UserAction.DELETE_FILE_SHARE))

            verify(exactly = 0) { repository.delete(any(), any()) }
        }

        @Test
        fun `unknown share is not found`() {
            setUp()
            every { repository.getShareByShareIdAndFileId("nope", entityId) } returns null

            assertNotFound(service.deleteShare(state.principalWith(userId = ownerId), entityId, "nope", UserAction.DELETE_FILE_SHARE))
        }

        @Test
        fun `database failure is an error`() {
            setUp()
            every { repository.delete(any(), any()) } throws RuntimeException("locked")

            assertError(service.deleteShare(state.principalWith(userId = ownerId), entityId, "my-link", UserAction.DELETE_FILE_SHARE))
        }
    }

    @Nested
    inner class ListingSharesOfAFile {

        private fun setUp() {
            every { fileService.isAllowedToAccessFile(any(), any(), any()) } returns Result.ok()
            every { entityService.getEntityIdByPath(any(), any()) } returns Result.ok(entityId)
        }

        private val mine = share(id = "mine", userId = ownerId)
        private val theirs = share(id = "theirs", userId = UlidCreator.getUlid())
        private val file: Path get() = Files.createFile(tempDir.resolve("file.txt"))

        @Test
        fun `ordinary user sees only their own shares`() {
            setUp()
            every { repository.getSharesByFileId(entityId) } returns listOf(mine, theirs)

            val result = service.getSharesByPath(state.principalWith(userId = ownerId), FilePath.of(file.toString()))

            assertEquals(listOf(mine), result.value)
        }

        @Test
        fun `holder of manage-all-shares permission sees every share`() {
            setUp()
            every { repository.getSharesByFileId(entityId) } returns listOf(mine, theirs)

            val result = service.getSharesByPath(state.principalWith(SystemPermission.MANAGE_ALL_FILE_SHARES), FilePath.of(file.toString()))

            assertEquals(listOf(mine, theirs), result.value)
        }

        @Test
        fun `file without an entity has no shares`() {
            setUp()
            every { entityService.getEntityIdByPath(any(), any()) } returns Result.notFound()

            val result = service.getSharesByPath(state.principalWith(), FilePath.of(file.toString()))

            assertEquals(emptyList<FileShare>(), result.value)
        }

        @Test
        fun `user without access to the file sees nothing`() {
            every { fileService.isAllowedToAccessFile(any(), any(), any()) } returns Result.reject("no access")

            assertRejected(service.getSharesByPath(state.principalWith(), FilePath.of(file.toString())))
        }
    }
}
