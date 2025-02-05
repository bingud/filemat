package org.filemat.server.common.util

import org.springframework.http.ResponseEntity

abstract class AController {

    fun ok(): ResponseEntity<String> = ResponseEntity.ok("ok")

    fun <T> ok(body: T) = ResponseEntity.ok(body)

    fun <T> bad(body: T) = ResponseEntity.badRequest().body(body)

    fun <T> internal(body: T) = ResponseEntity.internalServerError().body(body)

}