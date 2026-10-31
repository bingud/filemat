package org.filemat.server.config

import org.filemat.server.config.platform.Platform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PropsTest {

    @TempDir
    lateinit var tempDir: Path

    private val home = "home-folder"

    /** What [Props.resolveDataFolder] does with a raw path */
    private fun expected(first: String, vararg more: String) = Path.of(first, *more).toAbsolutePath().normalize()

    @Nested
    inner class ResolveDataFolder {

        @Test
        fun `FM_DATA_DIR wins on every OS`() {
            val env = mapOf("FM_DATA_DIR" to tempDir.toString(), "LOCALAPPDATA" to "ignored")

            assertEquals(tempDir, Props.resolveDataFolder(env, isWindows = true, userHome = home))
            assertEquals(tempDir, Props.resolveDataFolder(env, isWindows = false, userHome = home))
        }

        @Test
        fun `FM_DATA_DIR is made absolute and normalized`() {
            val env = mapOf("FM_DATA_DIR" to "a/./b/../data")

            assertEquals(expected("a", "data"), Props.resolveDataFolder(env, isWindows = false, userHome = home))
        }

        @Test
        fun `blank FM_DATA_DIR falls back to the default`() {
            val env = mapOf("FM_DATA_DIR" to "  ")

            assertEquals(expected("/var/lib/filemat"), Props.resolveDataFolder(env, isWindows = false, userHome = home))
        }

        @Test
        fun `POSIX default is var lib filemat`() {
            assertEquals(expected("/var/lib/filemat"), Props.resolveDataFolder(emptyMap(), isWindows = false, userHome = home))
        }

        @Test
        fun `Windows default is LOCALAPPDATA Filemat`() {
            val env = mapOf("LOCALAPPDATA" to tempDir.toString())

            assertEquals(tempDir.resolve("Filemat"), Props.resolveDataFolder(env, isWindows = true, userHome = home))
        }

        @Test
        fun `Windows without LOCALAPPDATA uses AppData Local in the home folder`() {
            assertEquals(
                expected(home, "AppData", "Local", "Filemat"),
                Props.resolveDataFolder(emptyMap(), isWindows = true, userHome = home),
            )
        }

        @Test
        fun `an invalid FM_DATA_DIR names the variable`() {
            val env = mapOf("FM_DATA_DIR" to "bad\u0000path")

            val error = assertThrows<IllegalArgumentException> { Props.resolveDataFolder(env, isWindows = false, userHome = home) }
            assertTrue(error.message!!.contains("FM_DATA_DIR"))
        }
    }

    @Nested
    inner class Defaults {

        @Test
        fun `files live in the data folder`() {
            assertEquals(Props.configuredDataFolderPath.resolve("filemat-server.db"), Props.databaseFile)
            assertEquals(Props.dataFolderPath.resolve("setup-code.txt").toString(), Props.setupCodeFile)
            assertEquals(Props.dataFolderPath.resolve("auth-code.txt").toString(), Props.authCodeFile)
        }

        @Test
        @EnabledOnOs(OS.WINDOWS)
        fun `Windows keeps uploads and thumbnails in the real data folder`() {
            assertTrue(Platform.isWindows)
            assertEquals(Props.dataFolderPath.resolve("uploads").toString(), Props.defaultUploadFolderPath)
            assertEquals(
                Props.dataFolderPath.resolve("cache").resolve("thumbnails").toString(),
                Props.defaultThumbnailCacheFolderPath,
            )
        }

        @Test
        @DisabledOnOs(OS.WINDOWS)
        fun `POSIX keeps the old upload and thumbnail folders`() {
            assertEquals("/tmp/filemat", Props.defaultUploadFolderPath)
            assertEquals("/var/cache/filemat", Props.defaultThumbnailCacheFolderPath)
        }
    }
}
