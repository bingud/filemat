package org.filemat.server.module.setting.service.component

import com.github.f4b6a3.ulid.UlidCreator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.config.Props
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.service.file.ThumbnailService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.setting.service.SettingService
import org.filemat.server.module.user.model.UserAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ThumbCacheSettingServiceSetupTest {

    private val settingService = mockk<SettingService>()
    private val filesystemService = mockk<FilesystemService>()
    private val thumbnailService = mockk<ThumbnailService>(relaxed = true)
    private val logService = mockk<LogService>(relaxed = true)
    private val service = ThumbCacheSettingService(
        settingService,
        filesystemService,
        thumbnailService,
        logService,
    )

    private val previousEnabled = runCatching { State.ThumbCache.isEnabled }.getOrNull()
    private val previousFolder = State.ThumbCache.folderPath
    private val previousMaxSize = State.ThumbCache.maxSizeMb
    private val previousMaxAge = State.ThumbCache.maxAge

    @BeforeEach
    fun stubDbWrites() {
        every { settingService.db_setSetting(any(), any()) } returns Result.ok()
        every { filesystemService.createFolder(any()) } returns Result.ok()
    }

    @AfterEach
    fun restore() {
        if (previousEnabled != null) State.ThumbCache.isEnabled = previousEnabled
        State.ThumbCache.folderPath = previousFolder
        State.ThumbCache.maxSizeMb = previousMaxSize
        State.ThumbCache.maxAge = previousMaxAge
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

        assertTrue(result.isSuccessful)
        assertTrue(State.ThumbCache.isEnabled)
        assertEquals(folder.pathString, State.ThumbCache.folderPath)
        assertEquals(Props.defaultThumbnailCacheMaxSizeMb, State.ThumbCache.maxSizeMb)
        assertEquals(Props.defaultThumbnailCacheMaxAgeSeconds, State.ThumbCache.maxAge)
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

        assertTrue(result.isSuccessful)
        assertFalse(State.ThumbCache.isEnabled)
        assertNull(State.ThumbCache.folderPath)
        assertNull(State.ThumbCache.maxSizeMb)
        assertNull(State.ThumbCache.maxAge)
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

        assertTrue(result.rejected)
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

        assertTrue(result.rejected)
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

        assertTrue(result.rejected)
        assertEquals("Expiration time is too low.", result.error)
        verify(exactly = 0) { settingService.db_setSetting(any(), any()) }
    }
}
