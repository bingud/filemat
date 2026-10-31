package org.filemat.server.config.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests each option of [FilenameRules]. The POSIX values are covered by `PathPolicyPosixTest`,
 * these use Windows-like settings so every branch runs on every OS.
 */
class FilenameRulesTest {

    private val windowsLike = FilenameRules(
        invalidChars = "<>:\"/\\|?*",
        rejectControlChars = true,
        reservedNames = listOf("CON", "NUL", "COM1"),
        reservedNamesIgnoreExtension = true,
        rejectTrailingDotOrSpace = true,
        maxLength = 10,
        lengthUnit = FilenameRules.UTF16_UNITS,
    )

    private fun FilenameRules.errorOf(name: String): String? = validate(name).let { if (it.isSuccessful) null else it.error }

    @Nested
    inner class Characters {

        @Test
        fun `every invalid character is rejected`() {
            windowsLike.invalidChars.forEach { char ->
                assertEquals("Name contains invalid characters.", windowsLike.errorOf("a${char}b"), "char: $char")
            }
        }

        @Test
        fun `control characters are rejected only when enabled`() {
            assertEquals("Name contains invalid characters.", windowsLike.errorOf("a\u0001b"))
            assertEquals("Name contains invalid characters.", windowsLike.errorOf("a\tb"))
            assertEquals(null, windowsLike.copy(rejectControlChars = false).errorOf("a\u0001b"))
        }

        @Test
        fun `empty name is rejected before anything else`() {
            assertEquals("Name is empty.", windowsLike.errorOf(""))
        }
    }

    @Nested
    inner class ReservedNames {

        @Test
        fun `reserved names match in any case`() {
            assertEquals("This name is reserved.", windowsLike.errorOf("con"))
            assertEquals("This name is reserved.", windowsLike.errorOf("Nul"))
        }

        @Test
        fun `reserved names match with an extension`() {
            assertEquals("This name is reserved.", windowsLike.errorOf("con.txt"))
            assertEquals("This name is reserved.", windowsLike.errorOf("COM1.a.b"))
        }

        @Test
        fun `trailing spaces before the extension still match`() {
            assertEquals("This name is reserved.", windowsLike.errorOf("CON .txt"))
        }

        @Test
        fun `names that only start with a reserved name pass`() {
            assertEquals(null, windowsLike.errorOf("CONSOLE"))
            assertEquals(null, windowsLike.errorOf("xCON.txt"))
            assertEquals(null, windowsLike.errorOf("COM10"))
        }

        @Test
        fun `without ignoring extensions only the exact name matches`() {
            val exact = windowsLike.copy(reservedNamesIgnoreExtension = false)
            assertEquals("This name is reserved.", exact.errorOf("CON"))
            assertEquals(null, exact.errorOf("con"))
            assertEquals(null, exact.errorOf("CON.txt"))
        }
    }

    @Nested
    inner class TrailingDotOrSpace {

        @Test
        fun `trailing dot or space is rejected when enabled`() {
            assertEquals("Name cannot end with a dot or space.", windowsLike.errorOf("name."))
            assertEquals("Name cannot end with a dot or space.", windowsLike.errorOf("name "))
        }

        @Test
        fun `leading or inner dots and spaces pass`() {
            assertEquals(null, windowsLike.errorOf(".hidden"))
            assertEquals(null, windowsLike.errorOf("a b.c"))
        }

        @Test
        fun `trailing dot or space pass when disabled`() {
            val lenient = windowsLike.copy(rejectTrailingDotOrSpace = false)
            assertEquals(null, lenient.errorOf("name."))
            assertEquals(null, lenient.errorOf("name "))
        }
    }

    @Nested
    inner class Length {

        @Test
        fun `UTF-16 units count each char once`() {
            assertEquals(null, windowsLike.errorOf("ä".repeat(10)))
            assertEquals("Name is too long.", windowsLike.errorOf("a".repeat(11)))
        }

        @Test
        fun `UTF-16 units count a surrogate pair twice`() {
            // U+1F600 is two UTF-16 units
            assertEquals(null, windowsLike.errorOf("😀".repeat(5)))
            assertEquals("Name is too long.", windowsLike.errorOf("😀".repeat(5) + "a"))
        }

        @Test
        fun `UTF-8 bytes count multi-byte chars by their encoded size`() {
            val bytes = windowsLike.copy(lengthUnit = FilenameRules.UTF8_BYTES)
            assertEquals(null, bytes.errorOf("ä".repeat(5)))
            assertEquals("Name is too long.", bytes.errorOf("ä".repeat(5) + "a"))
        }
    }

    @Test
    fun `a valid name passes all checks`() {
        assertTrue(windowsLike.validate("report.txt").isSuccessful)
    }
}
