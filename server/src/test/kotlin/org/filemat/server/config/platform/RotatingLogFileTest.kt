package org.filemat.server.config.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path

class RotatingLogFileTest {

    @TempDir
    lateinit var tempDir: Path

    private val folder get() = tempDir.resolve("logs")

    private fun read(name: String) = Files.readString(folder.resolve(name))

    private fun RotatingLogFile.print(text: String) {
        write(text.toByteArray())
        flush()
    }

    @Test
    fun `creates the folder and appends to an existing log`() {
        RotatingLogFile(folder).use { it.print("first\n") }
        RotatingLogFile(folder).use { it.print("second\n") }

        assertEquals("first\nsecond\n", read("filemat.log"))
    }

    @Test
    fun `a full log becomes filemat_1 and the oldest is dropped`() {
        RotatingLogFile(folder, maxFileBytes = 4, keptFiles = 2).use {
            it.print("aaaa")
            it.print("bbbb")
            it.print("cccc")
            it.print("dd")
        }

        assertEquals("dd", read("filemat.log"))
        assertEquals("cccc", read("filemat.1.log"))
        assertEquals("bbbb", read("filemat.2.log"))
        assertFalse(Files.exists(folder.resolve("filemat.3.log")))
    }

    @Test
    fun `an oversized log from the last run is rotated on the first write`() {
        RotatingLogFile(folder, maxFileBytes = 100).use { it.print("a".repeat(150)) }
        Files.move(folder.resolve("filemat.1.log"), folder.resolve("filemat.log"))

        RotatingLogFile(folder, maxFileBytes = 100).use { it.print("new\n") }

        assertEquals("new\n", read("filemat.1.log").takeLast(4))
        assertTrue(read("filemat.1.log").length > 100)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a log another program holds open keeps growing until it can be rotated`() {
        val log = RotatingLogFile(folder, maxFileBytes = 4)
        log.print("ab")

        // java.io opens without FILE_SHARE_DELETE, so the log can't be renamed
        FileInputStream(folder.resolve("filemat.log").toFile()).use {
            log.print("cd")
            log.print("ef")
        }
        assertEquals("abcdef", read("filemat.log"))

        log.print("gh")
        log.close()
        assertEquals("abcdefgh", read("filemat.1.log"))
        assertFalse(Files.exists(folder.resolve("filemat.log")))
    }

    @Test
    fun `after a write error the log is opened again once the retry delay has passed`() {
        var now = 0L
        // A file where the folder should be makes opening the log fail
        Files.writeString(folder, "not a folder")
        val log = RotatingLogFile(folder, retryDelayMillis = 1000, clock = { now })

        log.print("lost\n")
        Files.delete(folder)
        now = 999
        log.print("dropped\n")
        assertFalse(Files.exists(folder))

        now = 1000
        log.print("back\n")
        log.close()

        val text = read("filemat.log")
        assertTrue(text.startsWith("[log] Some output is missing here"), text)
        assertTrue(text.endsWith("back\n"), text)
        assertFalse(text.contains("lost") || text.contains("dropped"), text)
    }

    // ConsoleLogFile replaces System.out and System.err, which every test shares
    @Test
    @ResourceLock(Resources.SYSTEM_OUT, mode = ResourceAccessMode.READ_WRITE)
    @ResourceLock(Resources.SYSTEM_ERR, mode = ResourceAccessMode.READ_WRITE)
    fun `console output is copied with the console's bytes`() {
        val originalOut = System.out
        val originalErr = System.err
        val file = ConsoleLogFile.start(folder)
        try {
            println("out ünïcödé")
            System.err.println("err")
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
            file.close()
        }

        val charset = originalOut.charset()
        val text = String(Files.readAllBytes(folder.resolve("filemat.log")), charset)
        // As the console shows it, which may be "?" in a code page without these letters
        assertTrue(text.contains(String("out ünïcödé".toByteArray(charset), charset)), text)
        assertTrue(text.contains("err"), text)
    }

    @Test
    fun `the log file gets the output when the console fails`() {
        val file = RotatingLogFile(folder)
        val brokenConsole = object : java.io.OutputStream() {
            override fun write(b: Int) = throw java.io.IOException("no console")
        }
        val stream = java.io.PrintStream(ConsoleLogFile.TeeOutputStream(brokenConsole, file), true, Charsets.UTF_8)

        stream.println("still logged")
        stream.flush()
        file.close()

        assertTrue(read("filemat.log").contains("still logged"))
    }
}
