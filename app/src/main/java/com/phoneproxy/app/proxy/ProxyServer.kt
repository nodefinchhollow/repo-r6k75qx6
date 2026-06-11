package com.phoneproxy.app.proxy

import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Listens on a single port and serves both SOCKS5 and HTTP/HTTPS clients,
 * detecting the protocol from the first byte of each connection (0x05 -> SOCKS5,
 * anything else -> HTTP).
 *
 * Outbound sockets use the device's default network, so when a VPN is active on
 * the phone the forwarded traffic is routed through the VPN tunnel.
 */
class ProxyServer(private val config: ProxyConfig) {

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    private val executor: ThreadPoolExecutor =
        Executors.newCachedThreadPool() as ThreadPoolExecutor

    private val socks5 = Socks5Handler(config)
    private val http = HttpHandler(config)

    /** Binds the listening socket. Throws [IOException] if the port is unavailable. */
    fun start() {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(java.net.InetSocketAddress(InetAddress.getByName("0.0.0.0"), config.port))
        serverSocket = socket
        running = true

        val acceptThread = Thread { acceptLoop(socket) }
        acceptThread.isDaemon = true
        acceptThread.name = "proxy-accept"
        acceptThread.start()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (running) Log.w(TAG, "accept failed", e)
                break
            }
            executor.execute { serveClient(client) }
        }
    }

    private fun serveClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream())
            val output = client.getOutputStream()

            input.mark(1)
            val first = input.read()
            if (first == -1) {
                Relay.closeQuietly(client)
                return
            }
            input.reset()

            if (first == SOCKS5_VERSION) {
                socks5.handle(client, input, output)
            } else {
                http.handle(client, input, output)
            }
        } catch (e: IOException) {
            Log.d(TAG, "connection closed: ${e.message}")
        } finally {
            Relay.closeQuietly(client)
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        executor.shutdownNow()
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TAG = "ProxyServer"
        private const val SOCKS5_VERSION = 0x05
    }
}
