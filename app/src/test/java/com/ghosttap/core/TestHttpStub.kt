package com.ghosttap.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset

/**
 * Small JVM/Android-compatible HTTP stub for local unit tests.
 * Uses java.net.ServerSocket instead of the JDK desktop-only
 * com.sun.net.httpserver module, which is absent from Android's boot classpath.
 */
internal class TestHttpStub(private val status: Int, private val body: String) {
    private val server = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    @Volatile var lastRequestBody: String? = null
        private set
    @Volatile private var stopped = false
    private val worker = Thread {
        while (!stopped) {
            try {
                server.accept().use { socket -> respond(socket) }
            } catch (_: Exception) {
                if (!stopped) continue
            }
        }
    }.apply { isDaemon = true; name = "ghosttap-test-http-stub"; start() }

    private fun respond(socket: Socket) {
        socket.soTimeout = 3000
        val input = BufferedInputStream(socket.getInputStream())
        val headers = StringBuilder()
        var matched = 0
        val terminator = "\r\n\r\n"
        while (matched < terminator.length) {
            val ch = input.read()
            if (ch < 0) return
            headers.append(ch.toChar())
            matched = if (ch.toChar() == terminator[matched]) matched + 1
                else if (ch.toChar() == '\r') 1 else 0
        }
        val headerText = headers.toString()
        val contentLength = Regex("(?im)^Content-Length:\\s*(\\d+)\\s*$")
            .find(headerText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val request = ByteArray(contentLength)
        var offset = 0
        while (offset < request.size) {
            val count = input.read(request, offset, request.size - offset)
            if (count < 0) break
            offset += count
        }
        lastRequestBody = request.copyOf(offset).toString(Charset.forName("UTF-8"))
        val bytes = body.toByteArray(Charset.forName("UTF-8"))
        val phrase = when (status) {
            200 -> "OK"; 201 -> "Created"; 401 -> "Unauthorized"
            429 -> "Too Many Requests"; 500 -> "Internal Server Error"
            else -> "Stub"
        }
        val output = BufferedOutputStream(socket.getOutputStream())
        output.write("HTTP/1.1 $status $phrase\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charset.forName("UTF-8")))
        output.write(bytes)
        output.flush()
    }

    fun stop() {
        stopped = true
        runCatching { server.close() }
        runCatching { worker.join(250) }
    }
}
