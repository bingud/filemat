package org.filemat.server.module.setting.service

import com.github.f4b6a3.ulid.UlidCreator
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.filemat.server.common.State
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.service.TusService
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.setting.repository.SettingRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingServiceContentUrlTest {

    private val settingRepository = mockk<SettingRepository>(relaxed = true)
    private val logService = mockk<LogService>(relaxed = true)
    private val filesystemService = mockk<FilesystemService>(relaxed = true)
    private val tusService = mockk<TusService>(relaxed = true)
    private val fileService = mockk<FileService>(relaxed = true)
    private val service = SettingService(
        settingRepository,
        logService,
        filesystemService,
        tusService,
        fileService,
    )

    private val previousUrl = State.App.ContentBaseUrl.url
    private val previousUnauth = State.App.ContentBaseUrl.forUnauthenticated

    @AfterEach
    fun restore() {
        State.App.ContentBaseUrl.url = previousUrl
        State.App.ContentBaseUrl.forUnauthenticated = previousUnauth
    }

    private fun principal() = Principal(
        userId = UlidCreator.getUlid(),
        email = "admin@test",
        username = "admin",
        mfaTotpStatus = false,
        mfaTotpRequired = false,
        isBanned = false,
        roles = mutableListOf(),
        homeFolderPath = null,
    )

    @Test
    fun `stores a content base URL verbatim`() {
        val url = "https://203.0.113.10:8080/filemat/"
        val result = service.set_contentBaseUrl(principal(), url)

        assertTrue(result.isSuccessful)
        assertEquals(url, result.value)
        assertEquals(url, State.App.ContentBaseUrl.url)
        verify { settingRepository.setSetting(Props.Settings.contentBaseUrl, url, any()) }
    }

    @Test
    fun `allows clearing the content base URL`() {
        val result = service.set_contentBaseUrl(principal(), "")

        assertTrue(result.isSuccessful)
        assertEquals("", result.value)
        assertEquals("", State.App.ContentBaseUrl.url)
    }

    @Test
    fun `rejects a value that is not an http URL`() {
        val result = service.set_contentBaseUrl(principal(), "not-a-url")

        assertTrue(result.rejected)
        verify(exactly = 0) { settingRepository.setSetting(any(), any(), any()) }
    }

    @Test
    fun `rejects a relative path as a content base URL`() {
        val result = service.set_contentBaseUrl(principal(), "/api/v1/file/content")

        assertTrue(result.rejected)
        verify(exactly = 0) { settingRepository.setSetting(any(), any(), any()) }
    }

    @Test
    fun `rejects content base URL when locked by env`() {
        mockkObject(State.App.ContentBaseUrl)
        try {
            every { State.App.ContentBaseUrl.lockedByEnv } returns true

            val result = service.set_contentBaseUrl(principal(), "https://example.com")

            assertTrue(result.rejected)
            verify(exactly = 0) { settingRepository.setSetting(any(), any(), any()) }
        } finally {
            unmockkObject(State.App.ContentBaseUrl)
        }
    }

    @Test
    fun `rejects unauthenticated toggle when locked by env`() {
        mockkObject(State.App.ContentBaseUrl)
        try {
            every { State.App.ContentBaseUrl.forUnauthenticatedLockedByEnv } returns true

            val result = service.set_contentBaseUrlForUnauthenticated(principal(), true)

            assertTrue(result.rejected)
            verify(exactly = 0) { settingRepository.setSetting(any(), any(), any()) }
        } finally {
            unmockkObject(State.App.ContentBaseUrl)
        }
    }
}
