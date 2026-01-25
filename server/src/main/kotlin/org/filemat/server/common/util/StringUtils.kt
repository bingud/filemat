package org.filemat.server.common.util

import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
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

    fun measureByteSize(text: String): Long {
        return StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text)).limit().toLong()
    }
}

fun plural(text: String, count: Int) = if (count == 1) text else text + "s"