package com.phoneproxy.app.proxy

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minimal SOCKS5 (RFC 1928) server supporting the CONNECT command, with
 * optional username/password authentication (RFC 1929).
 *
 * Hostnames are resolved on the device, so DNS queries travel through the
 * phone's VPN tunnel together with the forwarded traffic.
 */
class Socks5Handler(private val config: ProxyConfig) {

    fun handle(client: Socket, input: InputStream, output: OutputStream) {
        val data = DataInputStream(input)

        // Greeting: VER already known to be 0x05 (consumed here).
        val version = data.readUnsignedByte()
        if (version != SOCKS_VERSION) return
        val methodCount = data.readUnsignedByte()
        val methods = ByteArray(methodCount)
        data.readFully(methods)

        val wantAuth = config.authEnabled
        val requiredMethod = if (wantAuth) METHOD_USERPASS else METHOD_NO_AUTH
        if (methods.none { (it.toInt() and 0xFF) == requiredMethod }) {
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), METHOD_NONE.toByte()))
            output.flush()
            return
        }
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), requiredMethod.toByte()))
        output.flush()

        if (wantAuth && !authenticate(data, output)) return

        // Request.
        val reqVersion = data.readUnsignedByte()
        if (reqVersion != SOCKS_VERSION) return
        val command = data.readUnsignedByte()
        data.readUnsignedByte() // RSV
        val addressType = data.readUnsignedByte()

        val host: String = when (addressType) {
            ATYP_IPV4 -> {
                val raw = ByteArray(4)
                data.readFully(raw)
                InetAddress.getByAddress(raw).hostAddress ?: return
            }
            ATYP_DOMAIN -> {
                val len = data.readUnsignedByte()
                val raw = ByteArray(len)
                data.readFully(raw)
                String(raw, Charsets.US_ASCII)
            }
            ATYP_IPV6 -> {
                val raw = ByteArray(16)
                data.readFully(raw)
                InetAddress.getByAddress(raw).hostAddress ?: return
            }
            else -> {
                reply(output, REP_ADDRESS_TYPE_NOT_SUPPORTED)
                return
            }
        }
        val port = data.readUnsignedShort()

        if (command != CMD_CONNECT) {
            reply(output, REP_COMMAND_NOT_SUPPORTED)
            return
        }

        val upstream: Socket
        try {
            upstream = Socket()
            upstream.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        } catch (e: IOException) {
            reply(output, replyCodeFor(e))
            return
        }

        reply(output, REP_SUCCEEDED)
        Relay.pipe(client, upstream)
    }

    private fun authenticate(data: DataInputStream, output: OutputStream): Boolean {
        val authVersion = data.readUnsignedByte()
        if (authVersion != AUTH_VERSION) return false
        val userLen = data.readUnsignedByte()
        val userRaw = ByteArray(userLen)
        data.readFully(userRaw)
        val passLen = data.readUnsignedByte()
        val passRaw = ByteArray(passLen)
        data.readFully(passRaw)

        val ok = String(userRaw, Charsets.UTF_8) == config.username &&
            String(passRaw, Charsets.UTF_8) == config.password
        output.write(byteArrayOf(AUTH_VERSION.toByte(), if (ok) 0x00 else 0x01))
        output.flush()
        return ok
    }

    private fun reply(output: OutputStream, replyCode: Int) {
        // VER, REP, RSV, ATYP=IPv4, BND.ADDR=0.0.0.0, BND.PORT=0
        output.write(
            byteArrayOf(
                SOCKS_VERSION.toByte(), replyCode.toByte(), 0x00, ATYP_IPV4.toByte(),
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            )
        )
        output.flush()
    }

    private fun replyCodeFor(e: IOException): Int {
        val message = e.message?.lowercase().orEmpty()
        return when {
            "refused" in message -> REP_CONNECTION_REFUSED
            "unreachable" in message -> REP_HOST_UNREACHABLE
            "timed out" in message || "timeout" in message -> REP_TTL_EXPIRED
            else -> REP_GENERAL_FAILURE
        }
    }

    companion object {
        private const val SOCKS_VERSION = 0x05
        private const val AUTH_VERSION = 0x01

        private const val METHOD_NO_AUTH = 0x00
        private const val METHOD_USERPASS = 0x02
        private const val METHOD_NONE = 0xFF

        private const val CMD_CONNECT = 0x01

        private const val ATYP_IPV4 = 0x01
        private const val ATYP_DOMAIN = 0x03
        private const val ATYP_IPV6 = 0x04

        private const val REP_SUCCEEDED = 0x00
        private const val REP_GENERAL_FAILURE = 0x01
        private const val REP_HOST_UNREACHABLE = 0x04
        private const val REP_CONNECTION_REFUSED = 0x05
        private const val REP_TTL_EXPIRED = 0x06
        private const val REP_COMMAND_NOT_SUPPORTED = 0x07
        private const val REP_ADDRESS_TYPE_NOT_SUPPORTED = 0x08

        private const val CONNECT_TIMEOUT_MS = 15_000
    }
}
