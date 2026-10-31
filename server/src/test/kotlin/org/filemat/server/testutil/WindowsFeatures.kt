package org.filemat.server.testutil

import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Skips a Windows test when a Windows feature it needs is missing on this machine: 8.3 names, `subst`, junctions,
 * case-sensitive folders or a profile's legacy junctions.
 * Set `-Dfilemat.requireWindowsFeatures=true` (or env FILEMAT_REQUIRE_WINDOWS_FEATURES=true) on the Windows test PC to fail instead,
 * so these tests can't pass by skipping.
 */
fun assumeWindowsFeature(isAvailable: Boolean, message: String) {
    if (isAvailable) return
    if (requiresWindowsFeatures()) throw AssertionError(message)
    assumeTrue(false, message)
}

internal fun requiresWindowsFeatures() = System.getProperty("filemat.requireWindowsFeatures") == "true" ||
    System.getenv("FILEMAT_REQUIRE_WINDOWS_FEATURES") == "true"
