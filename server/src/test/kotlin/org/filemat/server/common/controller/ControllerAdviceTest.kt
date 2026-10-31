package org.filemat.server.common.controller

import org.filemat.server.module.file.service.file.media.FfmpegBusyException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.nio.file.InvalidPathException

class ControllerAdviceTest {

    private val advice = ControllerAdvice()

    @Test
    fun `an invalid path is a bad request with the reason`() {
        val response = advice.controllerAdvice_invalidPath(InvalidPathException("a\u0000b", "Nul character not allowed"))

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertTrue(response.body!!.contains("Invalid path: Nul character not allowed."), response.body)
    }

    @Test
    fun `busy FFmpeg is service unavailable with a try-again message`() {
        val response = advice.controllerAdvice_ffmpegBusy(FfmpegBusyException())

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        assertTrue(response.body!!.contains("Try again in a moment."), response.body)
    }
}
