package com.example.hibari.net

/**
 * M2: Local HTTP/HTTPS proxy server for Secure DNS (DoH).
 *
 * Design (DESIGN.md §5.3):
 *  - Listens on 127.0.0.1:<random-high-port> only (never exposed externally)
 *  - Accepts HTTP and HTTPS CONNECT requests from WebView (via ProxyController)
 *  - Resolves hostnames using DohResolver (OkHttp DnsOverHttps)
 *  - Tunnels HTTPS via CONNECT — TLS is passed through, NOT decrypted (no MITM)
 *  - HTTP requests are forwarded after DoH resolution
 *
 * Security invariants:
 *  - Binds exclusively to 127.0.0.1 — not 0.0.0.0
 *  - Does not decrypt TLS (no MITM, no root CA injection)
 *  - Port is randomised at startup to avoid conflicts
 */
class LocalProxyServer(private val dohResolver: DohResolver) {

    var port: Int = 0
        private set

    private var isRunning = false

    suspend fun start() {
        // TODO M2: implement
        // 1. Bind ServerSocket to 127.0.0.1:0 (OS picks available port)
        // 2. Record port
        // 3. Accept loop in Dispatchers.IO coroutine
        // 4. For each connection: read first line
        //    - "CONNECT host:port" → DoH resolve host → open TCP tunnel, reply 200
        //    - "GET/POST … " → DoH resolve Host header → forward request/response
        throw NotImplementedError("LocalProxyServer will be implemented in M2")
    }

    fun stop() {
        isRunning = false
    }
}
