package org.filemat.server.common.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Field
import java.security.SecureRandom

class StringUtilsTest {

    @Test
    fun `randomString returns a string of the requested length from the expected character pool`() {
        val result = StringUtils.randomString(32)
        assertEquals(32, result.length)
        assertTrue(result.all { it.isLetterOrDigit() })
    }

    @Test
    fun `randomString does not repeat across calls`() {
        val first = StringUtils.randomString(32)
        val second = StringUtils.randomString(32)
        assertNotEquals(first, second)
    }
}
