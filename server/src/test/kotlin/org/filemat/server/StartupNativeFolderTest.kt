package org.filemat.server

import org.filemat.server.config.Props
import org.filemat.server.config.platform.WindowsNative
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclFileAttributeView
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Starts a JVM on a data folder, as `main` does up to Spring: a new one (test plan B2), or one that already exists.
 */
@EnabledOnOs(OS.WINDOWS)
class StartupNativeFolderTest {

    private lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        tempDir = Files.createTempDirectory("filemat-startup-test")
    }

    /**
     * Not `@TempDir`: an antivirus scan can hold the new DLLs for a moment after the JVM exits, and that must not fail the test
     */
    @AfterEach
    fun cleanup() {
        repeat(20) {
            if (tempDir.toFile().deleteRecursively()) return
            Thread.sleep(250)
        }
    }

    private fun startProbe(dataFolder: Path): String {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val builder = ProcessBuilder(
            java, "-Xlog:class+load=info",
            "-cp", System.getProperty("java.class.path"),
            StartupProbe::class.java.name,
        ).redirectErrorStream(true)
        builder.environment().apply {
            put("FM_DATA_DIR", dataFolder.toString())
            remove("JAVA_TOOL_OPTIONS")
        }
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), output)
        return output
    }

    @Test
    fun `a first start loads no JNA before jna_tmpdir is set and unpacks both DLLs into the data folder`() {
        val dataFolder = tempDir.resolve("new-data")
        val output = startProbe(dataFolder)

        assertTrue(StartupProbe.MARKER in output, output)
        assertFalse("com.sun.jna." in output.substringBefore(StartupProbe.MARKER), output)

        val results = output.lines().filter { it.startsWith("probe.") }.associate { it.substringBefore('=') to it.substringAfter('=').trim() }
        val nativeFolder = dataFolder.resolve("native").toString()
        assertEquals(nativeFolder, results["probe.jnaTmpdir"], output)
        assertEquals(nativeFolder, results["probe.sqliteTmpdir"], output)
        assertEquals("ok", results["probe.sqlite"], output)
        val files = results["probe.native"].orEmpty()
        assertTrue("jna" in files && "sqlite" in files, output)
    }

    @Test
    fun `a start on a data folder that already exists leaves its permissions and those of what is inside`() {
        val dataFolder = Files.createDirectory(tempDir.resolve("existing-data"))
        val other = Files.writeString(dataFolder.resolve("other.txt"), "x")
        val granted = ProcessBuilder("icacls", dataFolder.toString(), "/grant", "*S-1-5-32-545:(OI)(CI)R", "/T", "/Q")
            .redirectErrorStream(true).start().waitFor()
        assertEquals(0, granted)

        val output = startProbe(dataFolder)

        assertEquals("ok", output.lines().firstOrNull { it.startsWith("probe.sqlite=") }?.substringAfter('='), output)
        assertTrue("Other accounts can access the data folder" in output, output)
        val users = WindowsNative.accountNameOfSid("S-1-5-32-545") ?: error("BUILTIN\\Users not found")
        assertTrue(users in principals(dataFolder), "data folder")
        assertTrue(users in principals(other), "file inside")
        assertFalse(users in principals(dataFolder.resolve("filemat-server.db")), "database")
    }

    private fun principals(path: Path) =
        Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).acl.map { it.principal().name }.toSet()
}

/**
 * Entry point of the JVM that [StartupNativeFolderTest] starts. Runs `main` up to Spring.
 */
object StartupProbe {
    const val MARKER = "=== startup prepared ==="

    @JvmStatic
    fun main(args: Array<String>) {
        prepareStartup()
        println(MARKER)
        println("probe.jnaTmpdir=${System.getProperty("jna.tmpdir")}")
        println("probe.sqliteTmpdir=${System.getProperty("org.sqlite.tmpdir")}")
        initializeFileStructure()
        DriverManager.getConnection("jdbc:sqlite:${Props.databaseFile}").use { it.createStatement().execute("SELECT 1") }
        println("probe.sqlite=ok")
        val native = Path.of(System.getProperty("org.sqlite.tmpdir"))
        if (Files.isDirectory(native)) println("probe.native=${native.listDirectoryEntries().joinToString(",") { it.name }}")
    }
}
