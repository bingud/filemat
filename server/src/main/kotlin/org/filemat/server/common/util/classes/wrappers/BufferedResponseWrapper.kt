package org.filemat.server.common.util.classes.wrappers

import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import java.io.ByteArrayOutputStream
import java.io.CharArrayWriter
import java.io.PrintWriter
import java.nio.charset.Charset

class BufferedResponseWrapper(
    private val original: HttpServletResponse
) : HttpServletResponseWrapper(original) {

    private val byteBuffer = ByteArrayOutputStream()
    private val charBuffer = CharArrayWriter()
    private var writer: PrintWriter? = null
    private var servletOut: ServletOutputStream? = null

    private var bufferedStatus: Int = SC_OK
    private val bufferedHeaders = mutableMapOf<String, MutableList<String>>()

    // 1. intercept character writes
    override fun getWriter(): PrintWriter {
        if (servletOut != null) throw IllegalStateException("getOutputStream() already in use")
        if (writer == null) writer = PrintWriter(charBuffer)
        return writer!!
    }

    // 2. intercept binary writes
    override fun getOutputStream(): ServletOutputStream {
        if (writer != null) throw IllegalStateException("getWriter() already in use")
        if (servletOut == null) {
            servletOut = object : ServletOutputStream() {
                override fun isReady() = true
                override fun setWriteListener(listener: WriteListener) {}
                override fun write(b: Int) {
                    byteBuffer.write(b)
                }
                override fun flush() {
                    // no-op: prevent premature flush
                }
            }
        }
        return servletOut!!
    }

    // 3. disable container auto-flush/reset
    override fun flushBuffer() {}
    override fun resetBuffer() {
        byteBuffer.reset()
        charBuffer.reset()
    }

    // 4. buffer status & headers
    override fun setStatus(sc: Int) {
        bufferedStatus = sc
    }
    override fun sendError(sc: Int, msg: String?) {
        bufferedStatus = sc
    }
    override fun sendError(sc: Int) = sendError(sc, null)
    override fun sendRedirect(location: String) {
        bufferedStatus = SC_FOUND
        setHeader("Location", location)
    }
    override fun setHeader(name: String, value: String) {
        bufferedHeaders[name] = mutableListOf(value)
    }
    override fun addHeader(name: String, value: String) {
        bufferedHeaders.getOrPut(name) { mutableListOf() }.add(value)
    }

    /**
     * Copy buffered status, headers and body into the given HttpServletResponse.
     * Does not call any flush() or reset() on the destination—nothing is sent
     * to the client until the container commits at end‐of‐request.
     */
    fun copyTo(destination: HttpServletResponse) {
        // replay status
        destination.status = bufferedStatus

        // replay headers
        bufferedHeaders.forEach { (name, values) ->
            values.forEach { destination.addHeader(name, it) }
        }

        // ensure writer data is in charBuffer
        writer?.flush()

        // write body into destination’s buffer
        val out = destination.outputStream
        when {
            charBuffer.size() > 0 -> {
                val encoding = destination.characterEncoding ?: "UTF-8"
                val bytes = charBuffer
                    .toString()
                    .toByteArray(Charset.forName(encoding))
                destination.setContentLength(bytes.size)
                out.write(bytes)
            }
            byteBuffer.size() > 0 -> {
                val bytes = byteBuffer.toByteArray()
                destination.setContentLength(bytes.size)
                out.write(bytes)
            }
        }
    }
}
