package org.filemat.server.module.setting.service

import com.github.f4b6a3.ulid.UlidCreator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.common.State
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.Principal
import org.filemat.server.module.file.service.TusService
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.setting.repository.SettingRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingServiceContentUrlTest {

    private val settingRepository = mockk<SettingRepository>(relaxed = true)
    private val logService = mockk<LogService>(relaxed = true)
    private val filesystemService = mockk<FilesystemService>(relaxed = true)
    private val tusService = mockk<TusService>(relaxed = true)
    private val fileService = mockk<FileService>(relaxed = true)
    private val state = State(env = emptyMap())
    private val service = newService(state)

    private fun newService(state: State) = SettingService(
        settingRepository,
        logService,
        filesystemService,
        tusService,
        fileService,
        state,
    )

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
        assertEquals(url, state.app.contentBaseUrl.url)
        verify { settingRepository.setSetting(Props.Settings.contentBaseUrl, url, any()) }
    }

    @Test
    fun `allows clearing the content base URL`() {
        val result = service.set_contentBaseUrl(principal(), "")

        assertTrue(result.isSuccessful)
        assertEquals("", result.value)
        assertEquals("", state.app.contentBaseUrl.url)
    }

    @Test
    fun `trims a content base URL and treats whitespace as empty`() {
        val padded = service.set_contentBaseUrl(principal(), "  https://203.0.113.10:8080  ")
        assertTrue(padded.isSuccessful)
        assertEquals("https://203.0.113.10:8080", padded.value)

        val blank = service.set_contentBaseUrl(principal(), "   ")
        assertTrue(blank.isSuccessful)
        assertEquals("", blank.value)
        assertEquals("", state.app.contentBaseUrl.url)
    }

    @Test
    fun `rejects an http content base URL`() {
        val result = service.set_contentBaseUrl(principal(), "http://203.0.113.10:8080")

        assertTrue(result.rejected)
        verify(exactly = 0) { settingRepository.setSetting(any(), any(), any()) }
    }

    @Test
    fun `rejects a value that is not an https URL`() {
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
    fun `rejects a content base URL with a query or fragment`() {
        val query = service.set_contentBaseUrl(principal(), "https://cdn.example?tenant=x")
        val fragment = service.set_contentBaseUrl(principal(), "https://cdn.example/#files")

        assertTrue(query.rejected)
        assertTrue(fragment.rejected)
        verify(exactly = 0) { settingRepository.setSetting(any(), any(), any()) }
    }

    @Test
    fun `rejects content base URL when locked by env`() {
        val lockedService = newService(State(env = mapOf("FM_CONTENT_BASE_URL" to "https://env.example")))

        val result = lockedService.set_contentBaseUrl(principal(), "https://example.com")

        assertTrue(result.rejected)
        verify(exactly = 0) { settingRepository.setSetting(any(), any(), any()) }
    }

    @Test
    fun `rejects unauthenticated toggle when locked by env`() {
        val lockedService = newService(State(env = mapOf("FM_CONTENT_BASE_URL_FOR_UNAUTHENTICATED" to "false")))

        val result = lockedService.set_contentBaseUrlForUnauthenticated(principal(), true)

        assertTrue(result.rejected)
        verify(exactly = 0) { settingRepository.setSetting(any(), any(), any()) }
    }
}
