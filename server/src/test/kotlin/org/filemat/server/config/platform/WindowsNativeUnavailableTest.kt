package org.filemat.server.config.platform

import org.filemat.server.common.util.resolvePath
import org.filemat.server.module.file.model.FilePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Starts a JVM where JNA can't unpack its DLL, as under AppLocker or WDAC. Every Windows check must then refuse.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsNativeUnavailableTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `without JNA the data folder and the drives are refused`() {
        // A folder below a file can't be created
        val blockedFolder = Files.writeString(tempDir.resolve("file"), "x").resolve("native")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(
            java, "-Djna.nosys=true", "-Djna.tmpdir=$blockedFolder",
            "-cp", System.getProperty("java.class.path"),
            NativeUnavailableProbe::class.java.name, tempDir.toString(),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), output)

        val results = output.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        assertTrue(results["location"]?.contains("Windows system calls don't work") == true, output)
        assertEquals("true", results["notFound"], output)
        assertEquals("0", results["drives"], output)
        // Permissions then follow paths only, like on FAT drives
        assertEquals("false", results["fileIdsSupported"], output)
    }
}

/**
 * Entry point of the JVM that [WindowsNativeUnavailableTest] starts
 */
object NativeUnavailableProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val folder = Path.of(args[0])
        println("location=${DatabaseLocationPolicy.check(folder)}")
        println("notFound=${resolvePath(FilePath.ofAlreadyNormalized(folder), followSymlinks = true).notFound}")
        println("drives=${PathPolicy.virtualRootEntries().size}")
        println("fileIdsSupported=${FileIdentity.isSupported(folder)}")
    }
}
