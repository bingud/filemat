package org.filemat.server.module.auth.service

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.f4b6a3.ulid.Ulid
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.StringUtils
import org.filemat.server.common.util.unixNow
import org.filemat.server.config.Props
import org.filemat.server.module.auth.model.AuthToken
import org.filemat.server.module.auth.model.remainingMaxAge
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

data class ContentSessionTicket(
    val authToken: String,
    val userId: Ulid,
    val origin: String,
)

@Service
class ContentSessionService {

    private val tickets = Caffeine.newBuilder()
        .expireAfterWrite(Props.ContentSession.ticketTtlSeconds, TimeUnit.SECONDS)
        .maximumSize(100_000)
        .build<String, ContentSessionTicket>()

    fun mintTicket(authToken: AuthToken, origin: String): Result<String> {
        if (origin.isBlank()) return Result.reject("Missing request origin.")
        if (authToken.remainingMaxAge() <= 0) return Result.reject("Session expired.")

        val ticket = StringUtils.randomString(64)
        tickets.put(
            ticket,
            ContentSessionTicket(
                authToken = authToken.authToken,
                userId = authToken.userId,
                origin = origin,
            )
        )
        return Result.ok(ticket)
    }

    fun consumeTicket(ticket: String): Result<ContentSessionTicket> {
        if (ticket.isBlank()) return Result.reject("Ticket is invalid.")
        val value = tickets.getIfPresent(ticket) ?: return Result.reject("Ticket is invalid.")
        tickets.invalidate(ticket)
        return Result.ok(value)
    }

    fun cookieMaxAgeSeconds(authToken: AuthToken): Long {
        return minOf(Props.ContentSession.cookieMaxAgeSeconds, authToken.remainingMaxAge())
    }

    fun buildSetCookieHeader(token: String, maxAge: Long, secure: Boolean): String {
        val parts = mutableListOf(
            "${Props.Cookies.authToken}=$token",
            "Path=/",
            "HttpOnly",
            "Max-Age=$maxAge",
            "SameSite=None",
        )
        if (secure) {
            parts.add("Secure")
        }
        return parts.joinToString("; ")
    }
}
