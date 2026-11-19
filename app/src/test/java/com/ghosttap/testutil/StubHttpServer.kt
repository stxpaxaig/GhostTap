package com.ghosttap.testutil

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/** Minimal one-request loopback HTTP stub usable from Android JVM unit tests. */
class StubHttpServer(private val status: Int, private val responseBody: String) {
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = listener.localPort
    @Volatile var lastRequestBody: String? = null
        private set
    private val worker = Thread {
        try { listener.accept().use { serve(it) } } catch (_: Exception) { }
    }.apply { isDaemon = true; name = "ghosttap-test-http-stub"; start() }

    private fun readLine(input: InputStream): String {
        val bytes = ArrayList<Byte>()
        while (true) {
            val value = input.read()
            if (value < 0 || value == 10) break
            if (value != 13) bytes.add(value.toByte())
        }
        return bytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 5000
        val input = socket.getInputStream()
        readLine(input)
        var length = 0
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).equals("Content-Length", true))
                length = line.substring(colon + 1).trim().toIntOrNull() ?: 0
        }
        val bytesIn = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytesIn, offset, length - offset)
            if (count < 0) break
            offset += count
        }
        lastRequestBody = bytesIn.copyOf(offset).toString(StandardCharsets.UTF_8)
        val body = responseBody.toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"; 201 -> "Created"; 401 -> "Unauthorized"
            429 -> "Too Many Requests"; 500 -> "Internal Server Error"
            else -> "Stub"
        }
        val headers = "HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(headers.toByteArray(StandardCharsets.ISO_8859_1))
            write(body)
            flush()
        }
    }

    fun stop() {
        runCatching { listener.close() }
        runCatching { worker.join(1000) }
    }
}
