package org.filemat.server.common.controller

import org.filemat.server.common.util.controller.AController
import org.filemat.server.module.file.service.file.media.FfmpegBusyException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import java.nio.file.InvalidPathException


@ControllerAdvice
class ControllerAdvice : AController() {

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun controllerAdvice_missingServletRequestParameter(
        ex: MissingServletRequestParameterException
    ): ResponseEntity<String> {
        return bad("Request is missing the \"${ex.parameterName}\" field.")
    }

    @ExceptionHandler(InvalidPathException::class)
    fun controllerAdvice_invalidPath(
        ex: InvalidPathException
    ): ResponseEntity<String> {
        return bad("Invalid path: ${ex.reason}.")
    }

    /**
     * Content metadata when every FFmpeg slot is taken. The thumbnail endpoints answer 503 themselves.
     */
    @ExceptionHandler(FfmpegBusyException::class)
    fun controllerAdvice_ffmpegBusy(
        ex: FfmpegBusyException
    ): ResponseEntity<String> {
        return unavailable(FfmpegBusyException.MESSAGE)
    }

}