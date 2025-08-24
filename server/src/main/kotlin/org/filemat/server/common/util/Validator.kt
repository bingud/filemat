package org.filemat.server.common.util

import jakarta.mail.internet.InternetAddress

object Validator {

    fun email(e: String): String? {
        val valid = runCatching {
            InternetAddress(e).apply { validate() }
            true
        }.getOrElse { false }

        if (!valid) return "Email is invalid."
        return null
    }

    fun username(u: String): String? {
        if (u.isBlank()) return "Username is blank."
        if (u.length > 48) return "Username is too long."

        u.forEach { c ->
            if (!c.isLetterOrDigit() && c != '-' && c != '_') return "Username contains invalid characters."
        }

        return null
    }

    fun password(p: String?): String? {
        if (p.isNullOrBlank()) return "Password is blank."
        if (p.length < 4) return "Password is too short."
        if (p.length > 256) return "Password is too long."

        return null
    }

    fun roleName(s: String): String? {
        if (s.isBlank()) return "Role name is blank."
        if (s.length > 128) return "Role name is too long."
        return null
    }

    fun totp(s: String?): String? {
        if (s.isNullOrBlank()) return "2FA code is blank."
        if (s.length != 6) return "2FA code must be 6 digits long."
        if (s.any { !it.isDigit() }) return "2FA code must be 6 digits."
        return null
    }
}