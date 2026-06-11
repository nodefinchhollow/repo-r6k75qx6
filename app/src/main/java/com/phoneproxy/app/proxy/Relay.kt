package com.phoneproxy.app.proxy

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Bidirectional byte pump between a downstream (PC) socket and an upstream
 * (internet, via the phone VPN) socket. Runs the two directions on separate
 * threads and closes both sockets when either side reaches EOF or errors.
 */
object Relay {

    private const val BUFFER_SIZE = 16 * 1024

    fun pipe(downstream: Socket, upstream: Socket) {
        val closer = Runnable { closeQuietly(downstream); closeQuietly(upstream) }

        val upToDown = Thread {
            copy(safeInput(upstream), safeOutput(downstream))
            closer.run()
        }
        upToDown.isDaemon = true
        upToDown.start()

        // Run downstream -> upstream on the calling thread so the handler
        // thread stays alive for the lifetime of the connection.
        copy(safeInput(downstream), safeOutput(upstream))
        closer.run()

        try {
            upToDown.join()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun copy(input: InputStream?, output: OutputStream?) {
        if (input == null || output == null) return
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: IOException) {
            // Connection closed/reset; let the caller close both sockets.
        }
    }

    private fun safeInput(socket: Socket): InputStream? =
        try { socket.getInputStream() } catch (_: IOException) { null }

    private fun safeOutput(socket: Socket): OutputStream? =
        try { socket.getOutputStream() } catch (_: IOException) { null }

    fun closeQuietly(socket: Socket?) {
        try { socket?.close() } catch (_: IOException) { }
    }
}
