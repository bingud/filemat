package org.filemat.server.common.controller

import org.filemat.server.common.util.controller.AController
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler


@ControllerAdvice
class ControllerAdvice : AController() {

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun controllerAdvice_missingServletRequestParameter(
        ex: MissingServletRequestParameterException
    ): ResponseEntity<String> {
        return bad("Request is missing the \"${ex.parameterName}\" field.")
    }

}