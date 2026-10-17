package org.filemat.server.module.setting.service

import io.mockk.mockk
import io.mockk.verify
import org.filemat.server.common.State
import org.filemat.server.config.Props
import org.filemat.server.module.file.service.file.FileService
import org.filemat.server.module.file.service.filesystem.FilesystemService
import org.filemat.server.module.file.service.TusService
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.setting.repository.SettingRepository
import org.filemat.server.testutil.assertRejected
import org.filemat.server.testutil.assertSuccess
import org.filemat.server.testutil.testPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SettingServiceTest {

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


    @Test
    fun `stores a content base URL verbatim`() {
        val url = "https://203.0.113.10:8080/filemat/"
        val result = service.set_contentBaseUrl(testPrincipal(), url)

        assertSuccess(result)
        assertEquals(url, result.value)
        assertEquals(url, state.app.contentBaseUrl.url)
        verify { settingRepository.setSetting(Props.Settings.contentBaseUrl, url, any()) }
    }

    @Test
    fun `allows clearing the content base URL`() {
        val result = service.set_contentBaseUrl(testPrincipal(), "")

        assertSuccess(result)
        assertEquals("", result.value)
        assertEquals("", state.app.contentBaseUrl.url)
    }

    @Test
    fun `trims a content base URL and treats whitespace as empty`() {
        val padded = service.set_contentBaseUrl(testPrincipal(), "  https://203.0.113.10:8080  ")
        assertSuccess(padded)
        assertEquals("https://203.0.113.10:8080", padded.value)

        val blank = service.set_contentBaseUrl(testPrincipal(), "   ")
        assertSuccess(blank)
        assertEquals("", blank.value)
        assertEquals("", state.app.contentBaseUrl.url)
    }

    @Test
    fun `rejects an http content base URL`() {
        val result = service.set_contentBaseUrl(testPrincipal(), "http://203.0.113.10:8080")

        assertRejected(result)
        verify(exactly = 0) { settingRepository.setSetting(any(), any(), any()) }
    }

    @Test
    fun `rejects a value that is not an https URL`() {
        val result = service.set_contentBaseUrl(testPrincipal(), "not-a-url")

        assertRejected(result)
        verify(exactly = 0) { settingRepository.setSetting(any(), any(), any()) }
    }

    @Test
    fun `rejects a relative path as a content base URL`() {
        val result = service.set_contentBaseUrl(testPrincipal(), "/api/v1/file/content")

        assertRejected(result)
        verify(exactly = 0) { settingRepository.setSetting(any(), any(), any()) }
    }

    @Test
    fun `rejects a content base URL with a query or fragment`() {
        val query = service.set_contentBaseUrl(testPrincipal(), "https://cdn.example?tenant=x")
        val fragment = service.set_contentBaseUrl(testPrincipal(), "https://cdn.example/#files")

        assertRejected(query)
        assertRejected(fragment)
        verify(exactly = 0) { settingRepository.setSetting(any(), any(), any()) }
    }

    @Test
    fun `rejects content base URL when locked by env`() {
        val lockedService = newService(State(env = mapOf("FM_CONTENT_BASE_URL" to "https://env.example")))

        val result = lockedService.set_contentBaseUrl(testPrincipal(), "https://example.com")

        assertRejected(result)
        verify(exactly = 0) { settingRepository.setSetting(any(), any(), any()) }
    }

    @Test
    fun `rejects unauthenticated toggle when locked by env`() {
        val lockedService = newService(State(env = mapOf("FM_CONTENT_BASE_URL_FOR_UNAUTHENTICATED" to "false")))

        val result = lockedService.set_contentBaseUrlForUnauthenticated(testPrincipal(), true)

        assertRejected(result)
        verify(exactly = 0) { settingRepository.setSetting(any(), any(), any()) }
    }
}
