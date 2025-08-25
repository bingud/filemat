package org.filemat.server.common.util.controller

import org.filemat.server.common.util.formatMillisecondsToReadableTime
import org.springframework.http.ResponseEntity
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.OutputStream

abstract class AController {

    fun ok(): ResponseEntity<String> = ResponseEntity.ok("ok")

    fun <T> ok(body: T): ResponseEntity<T> = ResponseEntity.ok(body)

    fun bad(body: String, error: String): ResponseEntity<String> = ResponseEntity.badRequest().body(ErrorResponse(body, error).serialize())
    fun bad(body: String): ResponseEntity<String> = ResponseEntity.badRequest().body(ErrorResponse(body, "").serialize())
    fun notFound(): ResponseEntity<String> = ResponseEntity.notFound().build()
    fun unauthenticated(body: String, error: String): ResponseEntity<String> = ResponseEntity.status(401).body(ErrorResponse(body, error).serialize())
    fun internal(body: String, error: String): ResponseEntity<String> = ResponseEntity.internalServerError().body(ErrorResponse(body, error).serialize())
    fun internal(body: String): ResponseEntity<String> = ResponseEntity.internalServerError().body(ErrorResponse(body, "").serialize())

    fun rateLimited(millisUntilRefill: Long): ResponseEntity<String> =
        ResponseEntity.status(429).body(ErrorResponse("Too many requests. Try again in ${formatMillisecondsToReadableTime(millisUntilRefill)}", "ratelimit").serialize())

    fun streamBad(body: String, error: String) = streamResponse(ErrorResponse(body, error).serialize(), 400)
    fun streamNotFound() = ResponseEntity.status(404).build<StreamingResponseBody>()
    fun streamUnauthenticated(body: String, error: String) = streamResponse(ErrorResponse(body, error).serialize(), 401)
    fun streamInternal(body: String, error: String) = streamResponse(ErrorResponse(body, error).serialize(), 500)

    fun streamResponse(body: String, status: Int): ResponseEntity<StreamingResponseBody> {
        val streamBody = StreamingResponseBody { outputStream: OutputStream ->
            outputStream.write(body.toByteArray())
        }
        return ResponseEntity.status(status).body(streamBody)
    }
}