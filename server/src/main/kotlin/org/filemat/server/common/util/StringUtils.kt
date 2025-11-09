package org.filemat.server.common.util

import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.random.Random

object StringUtils {

    private val charPool: List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9')
    private val uppercaseCharPool: List<Char> = ('A'..'Z').toList()

    fun randomString(length: Int) = (1..length)
        .map { Random.nextInt(0, charPool.size).let { charPool[it] } }
        .joinToString("")

    fun randomLetterString(length: Int) = (1..length)
        .map { Random.nextInt(0, uppercaseCharPool.size).let { uppercaseCharPool[it] } }
        .joinToString("")

    /**
     * Hex sha256 hash
     */
    fun sha256Hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val encodedHash = digest.digest(text.encodeToByteArray())

        val hexString = StringBuilder()
        encodedHash.forEach { b ->
            val hex = Integer.toHexString(b.toInt() and 0xff)
            if (hex.length == 1) hexString.append('0')
            hexString.append(hex)
        }

        if (hexString.length != 64) throw IllegalStateException("Hex SHA256 hash of string is not 64 characters in StringUtils.")
        return hexString.toString()
    }

    /**
     * Serialize email aliases
     */
    fun normalizeEmail(email: String): String {
        val domain = email.substringAfter("@")
        val local = email.substringBefore("@")

        val dots = if (domain.contains("googlemail") || domain.contains("gmail")) {
            local.replace(".", "")
        } else local

        val plus = if (dots.contains("+")) {
            dots.substringBefore("+")
        } else dots

        return "$plus@$domain"
    }

    fun containsIPAddress(input: String): Boolean {
        val ipRegex = Regex("""\b((25[0-5]|2[0-4][0-9]|[0-1]?[0-9]{1,2})\.){3}(25[0-5]|2[0-4][0-9]|[0-1]?[0-9]{1,2})\b""")
        return ipRegex.containsMatchIn(input)
    }

    fun measureByteSize(text: String): Long {
        return StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text)).limit().toLong()
    }
}