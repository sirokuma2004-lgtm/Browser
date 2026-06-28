package com.example.hibari.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL

/**
 * Local HTTP/HTTPS proxy server for Secure DNS (DoH).
 *
 * Security invariants (DESIGN.md §5.3 / §7):
 *  - Binds ONLY to 127.0.0.1 (loopback). Never 0.0.0.0.
 *  - HTTPS traffic uses CONNECT tunneling — TLS is NOT decrypted (no MITM).
 *  - DoH resolution: all hostnames are resolved via [DohResolver], not system DNS.
 *  - Port is OS-assigned (random high port) to avoid conflicts.
 *
 * Protocol support:
 *  - CONNECT (used by WebView for HTTPS): resolves host via DoH, opens raw TCP
 *    tunnel, bridges streams bidirectionally. WebView's TLS passes through intact.
 *  - HTTP (plain http:// requests): resolves Host via DoH, forwards request,
 *    bridges response. Modern browsers send very few plain HTTP requests.
 */
class LocalProxyServer(
    private val resolver: DohResolver,
    private val scope: CoroutineScope,
) {
    private var serverSocket: ServerSocket? = null

    var port: Int = 0
        private set

    /** Starts the proxy. Returns the OS-assigned port on 127.0.0.1. */
    suspend fun start(): Int = withContext(Dispatchers.IO) {
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        port = ss.localPort

        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val client = ss.accept()
                    launch { handleClient(client) }
                } catch (_: SocketException) {
                    // Server socket closed — exit accept loop
                    break
                }
            }
        }
        port
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        port = 0
    }

    // ── Per-connection handling ───────────────────────────────────────────────

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        socket.soTimeout = 30_000
        try {
            val inputStream = socket.getInputStream()
            val firstLine = readRawLine(inputStream) ?: return@withContext
            if (firstLine.isBlank()) return@withContext

            if (firstLine.startsWith("CONNECT ", ignoreCase = true)) {
                handleConnect(socket, firstLine, inputStream)
            } else {
                handleHttp(socket, firstLine, inputStream)
            }
        } catch (_: IOException) {
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Handle HTTPS CONNECT tunnel.
     *
     * CONNECT host:443 HTTP/1.1
     * → resolve host via DoH
     * → open raw TCP to resolved IP
     * → reply 200, bridge both streams
     * → WebView's TLS handshake and all payload pass through intact (no MITM)
     */
    private suspend fun handleConnect(
        clientSocket: Socket,
        requestLine: String,
        clientIn: InputStream,
    ) = withContext(Dispatchers.IO) {
        val hostPort = requestLine.substringAfter("CONNECT ").substringBefore(" ").trim()
        val host = hostPort.substringBeforeLast(":")
        val port = hostPort.substringAfterLast(":").toIntOrNull() ?: 443

        // Drain remaining request headers (we don't forward them — CONNECT is hop-by-hop)
        drainHeaders(clientIn)

        val clientOut = clientSocket.getOutputStream()
        try {
            val addresses = resolver.resolve(host)
            val serverSocket = Socket(addresses.first(), port)
            serverSocket.soTimeout = 30_000

            // Inform client the tunnel is established
            clientOut.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            clientOut.flush()

            // Bidirectional bridge — TLS payload is opaque to us
            val serverIn = serverSocket.getInputStream()
            val serverOut = serverSocket.getOutputStream()
            val j1 = scope.launch(Dispatchers.IO) { bridge(clientIn, serverOut) }
            val j2 = scope.launch(Dispatchers.IO) { bridge(serverIn, clientOut) }
            j1.join()
            runCatching { serverSocket.close() }
            j2.join()
        } catch (_: Exception) {
            clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            clientOut.flush()
        }
    }

    /**
     * Handle plain HTTP request.
     *
     * GET http://host/path HTTP/1.1
     * → resolve Host via DoH
     * → forward request to real server (strip proxy-specific headers)
     * → bridge response back to client
     */
    private suspend fun handleHttp(
        clientSocket: Socket,
        requestLine: String,
        clientIn: InputStream,
    ) = withContext(Dispatchers.IO) {
        val parts = requestLine.trim().split(" ")
        if (parts.size < 3) return@withContext
        val method = parts[0]
        val rawUrl = parts[1]
        val httpVersion = parts[2]

        val url = try { URL(rawUrl) } catch (_: Exception) { return@withContext }
        val host = url.host
        val port = if (url.port > 0) url.port else 80
        val path = buildString {
            append(url.path.ifEmpty { "/" })
            if (!url.query.isNullOrEmpty()) append("?${url.query}")
        }

        // Collect headers, stripping proxy-specific ones
        val headers = mutableListOf<String>()
        var line = readRawLine(clientIn)
        while (!line.isNullOrBlank()) {
            if (!line.startsWith("Proxy-", ignoreCase = true) &&
                !line.startsWith("Proxy-Connection:", ignoreCase = true)
            ) {
                headers.add(line)
            }
            line = readRawLine(clientIn)
        }

        val clientOut = clientSocket.getOutputStream()
        try {
            val addresses = resolver.resolve(host)
            val serverSocket = Socket(addresses.first(), port)
            serverSocket.soTimeout = 30_000
            val serverOut = serverSocket.getOutputStream()

            // Forward modified request
            val requestBytes = buildString {
                append("$method $path $httpVersion\r\n")
                headers.forEach { append("$it\r\n") }
                append("\r\n")
            }.toByteArray(Charsets.ISO_8859_1)
            serverOut.write(requestBytes)
            serverOut.flush()

            // Bridge: client body → server, server response → client
            val serverIn = serverSocket.getInputStream()
            val j1 = scope.launch(Dispatchers.IO) { bridge(clientIn, serverOut) }
            val j2 = scope.launch(Dispatchers.IO) { bridge(serverIn, clientOut) }
            j1.join()
            runCatching { serverSocket.close() }
            j2.join()
        } catch (_: Exception) {
            clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            clientOut.flush()
        }
    }

    // ── Stream utilities ──────────────────────────────────────────────────────

    /** Read one line (up to CRLF or LF), return null on EOF. */
    private fun readRawLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    /** Drain all headers until blank line. */
    private fun drainHeaders(input: InputStream) {
        while (true) {
            val line = readRawLine(input) ?: break
            if (line.isBlank()) break
        }
    }

    /** Copy all bytes from [from] to [to] until EOF or error. */
    private fun bridge(from: InputStream, to: OutputStream) {
        try {
            val buf = ByteArray(8192)
            var n: Int
            while (from.read(buf).also { n = it } >= 0) {
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: IOException) {
        }
    }
}
