package com.tamperdroid.proxy

import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket

/**
 * Minimal HTTP(S) intercepting proxy.
 *
 * Point your device's Wi-Fi proxy at this process's IP + [port]. For HTTPS,
 * install the CA cert exported by [CertAuthority] on the device first,
 * otherwise clients will reject our generated leaf certs.
 *
 * Limitations (read before relying on this for anything serious):
 *  - No HTTP/2 (we force ALPN to http/1.1 on the client-facing TLS socket).
 *  - No chunked transfer-encoding support on the request/response body
 *    (only Content-Length). Add it if you need it.
 *  - No certificate pinning bypass. Apps that pin certs will simply fail
 *    the TLS handshake against us; that is a separate, harder problem
 *    (commonly solved with Frida, which is out of scope here).
 */
class ProxyServer(private val port: Int) {

    private val tag = "ProxyServer"
    private var serverSocket: ServerSocket? = null
    private val pool: ExecutorService = Executors.newCachedThreadPool()
    @Volatile private var running = false

    fun start() {
        running = true
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress("0.0.0.0", port))
        serverSocket = ss
        Log.i(tag, "Listening on port $port")

        pool.execute {
            while (running) {
                try {
                    val client = ss.accept()
                    pool.execute { handleClient(client) }
                } catch (e: IOException) {
                    if (running) Log.e(tag, "accept() failed", e)
                }
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
        pool.shutdownNow()
    }

    // ---- connection handling -------------------------------------------------

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 30_000
            val input = BufferedInputStream(client.getInputStream())
            val firstLine = readLine(input) ?: return

            if (firstLine.startsWith("CONNECT ")) {
                handleConnect(client, input, firstLine)
            } else {
                handlePlainHttp(client, input, firstLine)
            }
        } catch (e: Exception) {
            Log.w(tag, "connection error: ${e.message}")
        } finally {
            try { client.close() } catch (_: IOException) {}
        }
    }

    /** HTTPS: client sent "CONNECT host:port HTTP/1.1". We TLS-terminate locally. */
    private fun handleConnect(client: Socket, rawInput: InputStream, firstLine: String) {
        val target = firstLine.removePrefix("CONNECT ").substringBefore(" ").trim()
        val host = target.substringBefore(":")
        val port = target.substringAfter(":", "443").toIntOrNull() ?: 443

        // drain remaining CONNECT headers
        while (true) {
            val l = readLine(rawInput) ?: break
            if (l.isEmpty()) break
        }

        client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        client.getOutputStream().flush()

        val sslContext = CertAuthority.sslContextFor(host)
        val factory = sslContext.socketFactory
        val tlsClientSocket = factory.createSocket(client, host, port, true) as SSLSocket
        tlsClientSocket.useClientMode = false
        restrictToHttp11(tlsClientSocket)
        tlsClientSocket.startHandshake()

        val tlsIn = BufferedInputStream(tlsClientSocket.inputStream)
        val tlsOut = tlsClientSocket.outputStream

        // Keep-alive loop: handle multiple requests over the same TLS tunnel.
        while (true) {
            val reqLine = readLine(tlsIn) ?: break
            if (reqLine.isEmpty()) continue
            val exchange = parseRequest(reqLine, tlsIn, scheme = "https", defaultHost = host, defaultPort = port)
                ?: break
            forward(exchange, tlsOut)
        }
    }

    /** Plain HTTP, proxy-style request (absolute-form URI in the request line). */
    private fun handlePlainHttp(client: Socket, input: InputStream, firstLine: String) {
        val out = client.getOutputStream()
        var line = firstLine
        while (true) {
            val exchange = parseRequest(line, input, scheme = "http", defaultHost = null, defaultPort = 80)
                ?: break
            forward(exchange, out)
            val next = readLine(input) ?: break
            if (next.isEmpty()) break
            line = next
        }
    }

    private fun restrictToHttp11(socket: SSLSocket) {
        try {
            val params: SSLParameters = socket.sslParameters
            params.applicationProtocols = arrayOf("http/1.1")
            socket.sslParameters = params
        } catch (_: Throwable) {
            // applicationProtocols requires API 29+; fine to skip on older devices.
        }
    }

    // ---- request parsing -------------------------------------------------

    private fun parseRequest(
        requestLine: String,
        input: InputStream,
        scheme: String,
        defaultHost: String?,
        defaultPort: Int
    ): Exchange? {
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val method = parts[0]
        var rawTarget = parts[1]

        val headers = mutableListOf<Pair<String, String>>()
        while (true) {
            val l = readLine(input) ?: return null
            if (l.isEmpty()) break
            val idx = l.indexOf(":")
            if (idx > 0) headers.add(l.substring(0, idx).trim() to l.substring(idx + 1).trim())
        }

        var host = defaultHost
        var port = defaultPort
        var path = rawTarget

        if (rawTarget.startsWith("http://") || rawTarget.startsWith("https://")) {
            val uri = URI(rawTarget)
            host = uri.host
            port = if (uri.port != -1) uri.port else defaultPort
            path = uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
        } else if (host == null) {
            headers.firstOrNull { it.first.equals("Host", true) }?.let {
                host = it.second.substringBefore(":")
                port = it.second.substringAfter(":", defaultPort.toString()).toIntOrNull() ?: defaultPort
            }
        }
        if (host == null) return null

        val contentLength = headers.firstOrNull { it.first.equals("Content-Length", true) }?.second?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) readExactly(input, contentLength) else ByteArray(0)

        return Exchange(
            method = method,
            scheme = scheme,
            host = host!!,
            port = port,
            path = path,
            headers = headers,
            body = body
        )
    }

    // ---- forwarding + response relay -------------------------------------------------

    private fun forward(exchange: Exchange, clientOut: OutputStream) {
        ProxyBus.recordRequest(exchange)

        val toSend = if (ProxyBus.interceptEnabled) {
            ProxyBus.holdForEdit(exchange) ?: run {
                // dropped by the user
                clientOut.write("HTTP/1.1 502 Dropped by TamperDroid\r\n\r\n".toByteArray())
                return
            }
        } else exchange

        try {
            val upstream: Socket = if (toSend.scheme == "https") {
                val sf = javax.net.ssl.SSLSocketFactory.getDefault()
                sf.createSocket(toSend.host, toSend.port)
            } else {
                Socket(toSend.host, toSend.port)
            }
            upstream.use { sock ->
                val upOut = sock.getOutputStream()
                val reqText = StringBuilder()
                reqText.append("${toSend.method} ${toSend.path} HTTP/1.1\r\n")
                var hasHost = false
                for ((k, v) in toSend.headers) {
                    if (k.equals("Host", true)) hasHost = true
                    if (k.equals("Proxy-Connection", true)) continue
                    reqText.append("$k: $v\r\n")
                }
                if (!hasHost) reqText.append("Host: ${toSend.host}\r\n")
                reqText.append("Connection: close\r\n")
                reqText.append("\r\n")
                upOut.write(reqText.toString().toByteArray())
                if (toSend.body.isNotEmpty()) upOut.write(toSend.body)
                upOut.flush()

                val upIn = BufferedInputStream(sock.getInputStream())
                relayResponse(upIn, clientOut, toSend)
            }
        } catch (e: Exception) {
            Log.w(tag, "upstream error for ${toSend.host}: ${e.message}")
            try { clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()) } catch (_: IOException) {}
        }
    }

    private fun relayResponse(upIn: InputStream, clientOut: OutputStream, exchange: Exchange) {
        val statusLine = readLine(upIn) ?: return
        exchange.status = statusLine.split(" ").getOrNull(1)?.toIntOrNull()

        val respHeaders = mutableListOf<Pair<String, String>>()
        while (true) {
            val l = readLine(upIn) ?: break
            if (l.isEmpty()) break
            val idx = l.indexOf(":")
            if (idx > 0) respHeaders.add(l.substring(0, idx).trim() to l.substring(idx + 1).trim())
        }
        exchange.responseHeaders = respHeaders

        val contentLength = respHeaders.firstOrNull { it.first.equals("Content-Length", true) }?.second?.toIntOrNull()
        val respBody = if (contentLength != null && contentLength > 0) {
            readExactly(upIn, contentLength)
        } else {
            upIn.readBytes() // best-effort for connection: close responses without Content-Length
        }
        exchange.responseBody = respBody
        ProxyBus.recordResponse(exchange)

        clientOut.write((statusLine + "\r\n").toByteArray())
        for ((k, v) in respHeaders) clientOut.write("$k: $v\r\n".toByteArray())
        clientOut.write("\r\n".toByteArray())
        clientOut.write(respBody)
        clientOut.flush()
    }

    // ---- low-level IO helpers -------------------------------------------------

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (prev == '\r'.code && b == '\n'.code) {
                sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            prev = b
        }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n == -1) break
            read += n
        }
        return if (read == length) buf else buf.copyOf(read)
    }
}
