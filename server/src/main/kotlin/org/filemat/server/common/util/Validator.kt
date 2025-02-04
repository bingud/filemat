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

    fun password(p: String): String? {
        if (p.isBlank()) return "Password is blank."
        if (p.length < 4) return "Password is too short."
        if (p.length > 256) return "Password is too long."

        return null
    }

}