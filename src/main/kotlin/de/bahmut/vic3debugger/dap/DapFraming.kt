package de.bahmut.vic3debugger.dap

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** The DAP base protocol: a `Content-Length` header block, a blank line, then that many bytes of UTF-8 JSON. */
object DapFraming {
    private const val CONTENT_LENGTH = "content-length:"
    private const val MAX_HEADER_BYTES = 8 * 1024

    fun write(output: OutputStream, json: String) {
        val body = json.toByteArray(StandardCharsets.UTF_8)
        output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    /** Reads one message body, or returns null at a clean end of stream between messages. */
    fun read(input: InputStream): String? {
        var length = -1
        var sawHeader = false
        while (true) {
            val line = readHeaderLine(input, allowEof = !sawHeader) ?: return null
            if (line.isEmpty()) {
                if (!sawHeader) continue // tolerate stray blank lines between messages
                break
            }
            sawHeader = true
            if (line.startsWith(CONTENT_LENGTH, ignoreCase = true)) {
                length = line.substring(CONTENT_LENGTH.length).trim().toIntOrNull()
                    ?: throw IOException("Invalid Content-Length header: $line")
            }
        }
        if (length < 0) throw IOException("Message without a Content-Length header")
        return String(input.readNBytes(length).also {
            if (it.size < length) throw EOFException("Stream ended inside a message body")
        }, StandardCharsets.UTF_8)
    }

    private fun readHeaderLine(input: InputStream, allowEof: Boolean): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                if (allowEof && buffer.size() == 0) return null
                throw EOFException("Stream ended inside a header")
            }
            if (b == '\n'.code) break
            buffer.write(b)
            if (buffer.size() > MAX_HEADER_BYTES) throw IOException("Header line too long")
        }
        return buffer.toString(StandardCharsets.US_ASCII).trimEnd('\r')
    }
}
