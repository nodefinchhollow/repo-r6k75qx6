package com.phoneproxy.app.proxy

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * HTTP/HTTPS forward proxy.
 *
 * - `CONNECT host:port` opens a raw tunnel (used for HTTPS and any TLS traffic).
 * - Absolute-form requests (e.g. `GET http://host/path`) are rewritten to
 *   origin-form and forwarded to the target server.
 *
 * Optional proxy authentication uses the standard `Proxy-Authorization: Basic`
 * scheme (RFC 7235).
 */
class HttpHandler(private val config: ProxyConfig) {

    fun handle(client: Socket, input: InputStream, output: OutputStream) {
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 3) {
            writeError(output, "400 Bad Request")
            return
        }
        val method = parts[0]
        val target = parts[1]
        val httpVersion = parts[2]

        val headers = ArrayList<String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            headers.add(line)
        }

        if (config.authEnabled && !isAuthorized(headers)) {
            output.write(
                ("HTTP/1.1 407 Proxy Authentication Required\r\n" +
                    "Proxy-Authenticate: Basic realm=\"PhoneProxy\"\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
            )
            output.flush()
            return
        }

        if (method.equals("CONNECT", ignoreCase = true)) {
            handleConnect(client, output, target)
        } else {
            handleForward(client, output, method, target, httpVersion, headers)
        }
    }

    private fun handleConnect(client: Socket, output: OutputStream, target: String) {
        val (host, port) = splitHostPort(target, defaultPort = 443) ?: run {
            writeError(output, "400 Bad Request")
            return
        }
        val upstream = openUpstream(host, port) ?: run {
            writeError(output, "502 Bad Gateway")
            return
        }
        output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.flush()
        Relay.pipe(client, upstream)
    }

    private fun handleForward(
        client: Socket,
        output: OutputStream,
        method: String,
        target: String,
        httpVersion: String,
        headers: List<String>,
    ) {
        val uri = try { URI(target) } catch (_: Exception) { null }
        if (uri == null || uri.host == null) {
            writeError(output, "400 Bad Request")
            return
        }
        val host = uri.host
        val port = if (uri.port != -1) uri.port else 80
        val path = buildString {
            append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
            if (!uri.rawQuery.isNullOrEmpty()) append("?").append(uri.rawQuery)
        }

        val upstream = openUpstream(host, port) ?: run {
            writeError(output, "502 Bad Gateway")
            return
        }

        val rebuilt = StringBuilder()
        rebuilt.append("$method $path $httpVersion\r\n")
        var hasHost = false
        for (header in headers) {
            val lower = header.lowercase()
            when {
                lower.startsWith("proxy-") -> continue
                lower.startsWith("connection:") -> continue
                lower.startsWith("host:") -> hasHost = true
            }
            rebuilt.append(header).append("\r\n")
        }
        if (!hasHost) {
            val hostHeader = if (port == 80) host else "$host:$port"
            rebuilt.append("Host: $hostHeader\r\n")
        }
        rebuilt.append("Connection: close\r\n")
        rebuilt.append("\r\n")

        try {
            val upstreamOut = upstream.getOutputStream()
            upstreamOut.write(rebuilt.toString().toByteArray(Charsets.US_ASCII))
            upstreamOut.flush()
        } catch (_: IOException) {
            Relay.closeQuietly(upstream)
            writeError(output, "502 Bad Gateway")
            return
        }

        Relay.pipe(client, upstream)
    }

    private fun openUpstream(host: String, port: Int): Socket? = try {
        Socket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
    } catch (_: IOException) {
        null
    }

    private fun isAuthorized(headers: List<String>): Boolean {
        val expected = "Basic " + Base64.encodeToString(
            "${config.username}:${config.password}".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        return headers.any {
            val lower = it.lowercase()
            lower.startsWith("proxy-authorization:") &&
                it.substringAfter(":").trim() == expected
        }
    }

    private fun splitHostPort(value: String, defaultPort: Int): Pair<String, Int>? {
        val trimmed = value.trim()
        val lastColon = trimmed.lastIndexOf(':')
        // IPv6 literals contain colons; only treat the final segment as a port
        // when it is purely numeric.
        if (lastColon == -1) return trimmed to defaultPort
        val maybePort = trimmed.substring(lastColon + 1)
        val port = maybePort.toIntOrNull() ?: return trimmed to defaultPort
        val host = trimmed.substring(0, lastColon).trim('[', ']')
        if (host.isEmpty()) return null
        return host to port
    }

    private fun writeError(output: OutputStream, status: String) {
        try {
            output.write(
                ("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .toByteArray(Charsets.US_ASCII)
            )
            output.flush()
        } catch (_: IOException) {
        }
    }

    /** Reads a single CRLF-terminated line as ASCII, without buffering past it. */
    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(64)
        var sawByte = false
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (sawByte) buffer.toString("US-ASCII") else null
            }
            sawByte = true
            if (b == '\n'.code) break
            if (b != '\r'.code) buffer.write(b)
        }
        return buffer.toString("US-ASCII")
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
    }
}
