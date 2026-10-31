package org.filemat.server.config.platform

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Copies everything printed to the console into `<data folder>\logs\filemat.log` (Q53).
 * The portable Windows app has no journald or Docker log, so this file is the only record of a crash.
 */
object ConsoleLogFile {

    fun start(folder: Path): RotatingLogFile {
        val file = RotatingLogFile(folder)
        System.setOut(tee(System.out, file))
        System.setErr(tee(System.err, file))
        return file
    }

    // The file gets the same bytes as the console
    private fun tee(console: PrintStream, file: OutputStream) = PrintStream(TeeOutputStream(console, file), true, console.charset())

    private class TeeOutputStream(private val console: OutputStream, private val file: OutputStream) : OutputStream() {
        override fun write(b: Int) {
            console.write(b)
            file.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            console.write(b, off, len)
            file.write(b, off, len)
        }

        override fun flush() {
            console.flush()
            file.flush()
        }
    }
}

/**
 * Appends to `filemat.log` in [folder]. At [maxFileBytes] it becomes `filemat.1.log`, and up to [keptFiles] older files are kept.
 * A file error stops the copy, never the console.
 */
class RotatingLogFile(
    private val folder: Path,
    private val maxFileBytes: Long = 10L * 1024 * 1024,
    private val keptFiles: Int = 9,
) : OutputStream() {

    private val current = folder.resolve("filemat.log")
    private var stream: OutputStream? = null
    private var size = 0L
    private var rotateAt = maxFileBytes
    private var failed = false

    @Synchronized
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (failed) return
        try {
            val out = stream ?: open()
            out.write(b, off, len)
            size += len
            if (size >= rotateAt) rotate()
        } catch (_: IOException) {
            failed = true
            close()
        }
    }

    @Synchronized
    override fun flush() {
        try {
            stream?.flush()
        } catch (_: IOException) {
            failed = true
            close()
        }
    }

    @Synchronized
    override fun close() {
        runCatching { stream?.close() }
        stream = null
    }

    private fun open(): OutputStream {
        Files.createDirectories(folder)
        val out = BufferedOutputStream(Files.newOutputStream(current, StandardOpenOption.CREATE, StandardOpenOption.APPEND))
        stream = out
        size = Files.size(current)
        return out
    }

    private fun rotate() {
        close()
        try {
            for (index in keptFiles - 1 downTo 1) {
                val older = folder.resolve("filemat.$index.log")
                if (Files.exists(older)) Files.move(older, folder.resolve("filemat.${index + 1}.log"), StandardCopyOption.REPLACE_EXISTING)
            }
            Files.move(current, folder.resolve("filemat.1.log"), StandardCopyOption.REPLACE_EXISTING)
            rotateAt = maxFileBytes
        } catch (_: IOException) {
            // Another program has a log open without sharing delete. Keep appending and try again later.
            rotateAt = size + maxFileBytes
        }
    }
}
