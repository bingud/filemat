package org.filemat.server.common.util.controller

import org.springframework.http.ResponseEntity

abstract class AController {

    fun ok(): ResponseEntity<String> = ResponseEntity.ok("ok")

    fun <T> ok(body: T): ResponseEntity<T> = ResponseEntity.ok(body)

    fun bad(body: String, error: String): ResponseEntity<String> = ResponseEntity.badRequest().body(ErrorResponse(body, error).serialize())

    fun internal(body: String, error: String): ResponseEntity<String> = ResponseEntity.internalServerError().body(ErrorResponse(body, error).serialize())

}