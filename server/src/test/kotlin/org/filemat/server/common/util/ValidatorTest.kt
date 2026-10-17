package org.filemat.server.common.util

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource

/** Each validator returns `null` for valid input and an error message otherwise. */
class ValidatorTest {

    @Nested
    inner class Username {

        @ParameterizedTest
        @ValueSource(strings = ["alice", "Alice_01", "a-b", "x", "ü1"])
        fun `accepts letters, digits, dash and underscore`(name: String) {
            assertNull(Validator.username(name))
        }

        @ParameterizedTest
        @ValueSource(strings = ["", "   ", "a b", "a.b", "a/b", "../x", "a@b", "a\n"])
        fun `rejects blank names and other characters`(name: String) {
            assertNotNull(Validator.username(name))
        }

        @Test
        fun `length limit is 48 characters`() {
            assertNull(Validator.username("a".repeat(48)))
            assertNotNull(Validator.username("a".repeat(49)))
        }
    }

    @Nested
    inner class Password {

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = ["", "    ", "abc"])
        fun `rejects missing, blank and too short`(password: String?) {
            assertNotNull(Validator.password(password))
        }

        @Test
        fun `length must be between 4 and 256`() {
            assertNull(Validator.password("abcd"))
            assertNull(Validator.password("a".repeat(256)))
            assertNotNull(Validator.password("a".repeat(257)))
        }

        @Test
        fun `spaces and symbols are allowed`() {
            assertNull(Validator.password("pass word!#"))
        }
    }

    @Nested
    inner class Email {

        @ParameterizedTest
        @ValueSource(strings = ["a@b.com", "first.last@example.org", "a+tag@example.co"])
        fun `accepts valid addresses`(email: String) {
            assertNull(Validator.email(email))
        }

        @ParameterizedTest
        @ValueSource(strings = ["", "plain", "@example.com", "a@", "a b@example.com", "a@@example.com"])
        fun `rejects invalid addresses`(email: String) {
            assertNotNull(Validator.email(email))
        }
    }

    @Nested
    inner class RoleName {

        @ParameterizedTest
        @ValueSource(strings = ["", "   "])
        fun `rejects blank`(name: String) {
            assertNotNull(Validator.roleName(name))
        }

        @Test
        fun `length limit is 128 characters`() {
            assertNull(Validator.roleName("a".repeat(128)))
            assertNotNull(Validator.roleName("a".repeat(129)))
        }
    }

    @Nested
    inner class Totp {

        @ParameterizedTest
        @ValueSource(strings = ["000000", "123456"])
        fun `accepts six digits`(code: String) {
            assertNull(Validator.totp(code))
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = ["", "  ", "12345", "1234567", "12345a", "12 456"])
        fun `rejects everything else`(code: String?) {
            assertNotNull(Validator.totp(code))
        }
    }
}
