package org.filemat.server.module.setting.service.component

import com.github.f4b6a3.ulid.UlidCreator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.common.model.Result
import org.filemat.server.common.State
import org.filemat.server.config.Props
import org.filemat.server.config.platform.PathPolicy
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.file.ThumbnailService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.setting.service.SettingService
import org.filemat.server.module.user.model.UserAction
import org.filemat.server.testutil.assertRejected
import org.filemat.server.testutil.assertSuccess
import org.filemat.server.testutil.symlinkOrSkip
import org.filemat.server.testutil.testPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ThumbCacheSettingServiceTest {

    private val settingService = mockk<SettingService>()
    private val filesystemService = mockk<FilesystemService>()
    private val thumbnailService = mockk<ThumbnailService>(relaxed = true)
    private val logService = mockk<LogService>(relaxed = true)
    private val state = State(env = emptyMap())
    private val service = ThumbCacheSettingService(
        settingService,
        filesystemService,
        thumbnailService,
        logService,
        state,
    )

    @BeforeEach
    fun stubDbWrites() {
        every { settingService.db_setSetting(any(), any()) } returns Result.ok()
        every { filesystemService.createFolder(any()) } returns Result.ok()
    }

    @Test
    fun `saveForSetup persists enabled cache settings and creates the folder`() {
        val folder = FilePath.of(Props.defaultThumbnailCacheFolderPath)
        val result = service.saveForSetup(
            initiatorId = UlidCreator.getUlid(),
            isEnabled = true,
            folderPath = folder,
            maxSizeMb = Props.defaultThumbnailCacheMaxSizeMb,
            maxAge = Props.defaultThumbnailCacheMaxAgeSeconds,
        )

        assertSuccess(result)
        assertTrue(state.thumbCache.isEnabled)
        assertEquals(folder.pathString, state.thumbCache.folderPath)
        assertEquals(Props.defaultThumbnailCacheMaxSizeMb, state.thumbCache.maxSizeMb)
        assertEquals(Props.defaultThumbnailCacheMaxAgeSeconds, state.thumbCache.maxAge)
        verify { settingService.db_setSetting(Props.Settings.ThumbCache.enabled, "true") }
        verify { settingService.db_setSetting(Props.Settings.ThumbCache.folderPath, folder.pathString) }
        verify { settingService.db_setSetting(Props.Settings.ThumbCache.maxSizeMb, "512") }
        verify { settingService.db_setSetting(Props.Settings.ThumbCache.maxAge, "604800") }
        verify { filesystemService.createFolder(folder) }
        verify {
            logService.info(
                type = any(),
                action = UserAction.APP_SETUP,
                description = any(),
                message = any(),
                initiatorId = any(),
                initiatorIp = any(),
                targetId = any(),
                meta = any(),
            )
        }
    }

    @Test
    fun `saveForSetup allows disabling without a folder and does not create one`() {
        val result = service.saveForSetup(
            initiatorId = UlidCreator.getUlid(),
            isEnabled = false,
            folderPath = null,
            maxSizeMb = null,
            maxAge = null,
        )

        assertSuccess(result)
        assertFalse(state.thumbCache.isEnabled)
        assertNull(state.thumbCache.folderPath)
        assertNull(state.thumbCache.maxSizeMb)
        assertNull(state.thumbCache.maxAge)
        verify { settingService.db_setSetting(Props.Settings.ThumbCache.enabled, "false") }
        verify(exactly = 0) { filesystemService.createFolder(any()) }
    }

    @Test
    fun `saveForSetup rejects enabled caching without a folder`() {
        val result = service.saveForSetup(
            initiatorId = UlidCreator.getUlid(),
            isEnabled = true,
            folderPath = null,
            maxSizeMb = 512,
            maxAge = 3600,
        )

        assertRejected(result)
        assertEquals("Thumbnail cache folder is required when caching is enabled.", result.error)
        verify(exactly = 0) { settingService.db_setSetting(any(), any()) }
    }

    @Test
    fun `saveForSetup rejects max size below 1`() {
        val result = service.saveForSetup(
            initiatorId = UlidCreator.getUlid(),
            isEnabled = false,
            folderPath = null,
            maxSizeMb = 0,
            maxAge = null,
        )

        assertRejected(result)
        assertEquals("Max Size parameter is too low.", result.error)
        verify(exactly = 0) { settingService.db_setSetting(any(), any()) }
    }

    @Test
    fun `saveForSetup rejects expiration below 1 second`() {
        val result = service.saveForSetup(
            initiatorId = UlidCreator.getUlid(),
            isEnabled = false,
            folderPath = null,
            maxSizeMb = null,
            maxAge = 0,
        )

        assertRejected(result)
        assertEquals("Expiration time is too low.", result.error)
        verify(exactly = 0) { settingService.db_setSetting(any(), any()) }
    }

    @Test
    fun `saveForSetup rejects the root as the cache folder`() {
        val result = service.saveForSetup(
            initiatorId = UlidCreator.getUlid(),
            isEnabled = true,
            folderPath = FilePath.of("/"),
            maxSizeMb = null,
            maxAge = null,
        )

        assertRejected(result)
        assertEquals("Thumbnail cache folder: A drive or the root folder can't be used.", result.error)
        verify(exactly = 0) { settingService.db_setSetting(any(), any()) }
    }

    @Test
    fun `set_thumbnail rejects the root as the cache folder`() {
        val result = service.set_thumbnail(
            user = testPrincipal(),
            isEnabled = true,
            deleteCache = null,
            moveCache = null,
            folderPath = FilePath.of("/a/.."),
            maxSizeMb = null,
            maxAge = null,
        )

        assertRejected(result)
        assertEquals("Thumbnail cache folder: A drive or the root folder can't be used.", result.error)
        verify(exactly = 0) { settingService.db_setSetting(any(), any()) }
    }

    @Test
    fun `the data folder and the folders that contain it are refused as the cache folder`(@TempDir tempDir: Path) {
        val dataFolder = Files.createDirectory(tempDir.resolve("data")).toRealPath()
        val service = ThumbCacheSettingService(settingService, filesystemService, thumbnailService, logService, State(env = emptyMap(), dataFolderPath = dataFolder))
        fun set(folder: Path) = service.set_thumbnail(testPrincipal(), null, null, null, FilePath.ofAlreadyNormalized(folder), null, null)

        listOf(dataFolder, tempDir).forEach { folder ->
            val result = set(folder)
            assertRejected(result)
            assertEquals("Thumbnail cache folder: The Filemat data folder, or a folder that contains it, can't be used.", result.error)
        }
        assertSuccess(set(dataFolder.resolve("cache")))
    }

    @Test
    fun `the cache folder is stored as its real path`(@TempDir tempDir: Path) {
        val real = Files.createDirectory(tempDir.toRealPath().resolve("Cache"))
        val link = tempDir.toRealPath().resolve("cache-link")
        symlinkOrSkip(link, real)

        val result = service.set_thumbnail(testPrincipal(), null, null, null, FilePath.ofAlreadyNormalized(link), null, null)

        assertSuccess(result)
        assertEquals(real, result.value?.path)
        assertEquals(PathPolicy.toApiString(real), state.thumbCache.folderPath)
        verify { settingService.db_setSetting(Props.Settings.ThumbCache.folderPath, PathPolicy.toApiString(real)) }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a cache folder typed in another case is stored in its real case`(@TempDir tempDir: Path) {
        val real = Files.createDirectory(tempDir.toRealPath().resolve("Cache"))

        val result = service.set_thumbnail(testPrincipal(), null, null, null, FilePath.ofAlreadyNormalized(real.resolveSibling("cache")), null, null)

        assertSuccess(result)
        assertEquals(PathPolicy.toApiString(real), state.thumbCache.folderPath)
    }
}
