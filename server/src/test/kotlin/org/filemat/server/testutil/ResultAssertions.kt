package org.filemat.server.testutil

import org.filemat.server.common.model.Result
import org.junit.jupiter.api.Assertions.assertTrue

/** Fails with the full [Result] in the message, so a failure shows what was returned instead of "expected true". */
fun assertSuccess(result: Result<*>) = assertTrue(result.isSuccessful, "Expected success but got: $result")

fun assertRejected(result: Result<*>) = assertTrue(result.rejected, "Expected rejection but got: $result")

fun assertError(result: Result<*>) = assertTrue(result.hasError, "Expected error but got: $result")

/** Any unsuccessful result: rejected, not found or error. Prefer a more specific assertion when the kind matters. */
fun assertNotSuccessful(result: Result<*>) = assertTrue(result.isNotSuccessful, "Expected an unsuccessful result but got: $result")

fun assertNotFound(result: Result<*>) = assertTrue(result.notFound, "Expected not found but got: $result")
