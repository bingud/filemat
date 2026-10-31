package org.filemat.server.config.platform

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Paths

class DatabaseLocationPolicyTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `a local folder is accepted`() {
        assertNull(DatabaseLocationPolicy.check(tempDir))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a UNC path is rejected on Windows`() {
        val error = DatabaseLocationPolicy.check(Paths.get("\\\\server\\share\\filemat"))

        assertTrue(error?.contains("network path") == true, error)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a data folder whose drive type can't be read is rejected`() {
        mockkObject(WindowsNative)
        try {
            every { WindowsNative.isOnNetworkDrive(any()) } returns null

            val error = DatabaseLocationPolicy.check(tempDir)

            assertTrue(error?.contains("can't check whether the data folder") == true, error)
        } finally {
            unmockkObject(WindowsNative)
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `nothing is rejected outside Windows`() {
        assertNull(DatabaseLocationPolicy.check(Paths.get("/mnt/nfs/filemat")))
    }
}
