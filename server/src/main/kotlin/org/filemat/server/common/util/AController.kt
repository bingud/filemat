package org.filemat.server.common.util

import org.springframework.http.ResponseEntity

abstract class AController {

    fun ok(): ResponseEntity<String> {
        return ResponseEntity.ok("ok")
    }

    fun <T> ok(body: T): ResponseEntity<T> {
        return ResponseEntity.ok(body)
    }

    fun <T> bad(body: T): ResponseEntity<T> {
        return ResponseEntity.badRequest().body(body)
    }

}