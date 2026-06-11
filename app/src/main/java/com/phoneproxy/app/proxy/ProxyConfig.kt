package com.phoneproxy.app.proxy

/**
 * Immutable runtime configuration for the proxy server.
 *
 * The same listening port serves both SOCKS (4/5) and HTTP/HTTPS clients;
 * the protocol is detected from the first byte of each connection.
 */
data class ProxyConfig(
    val port: Int = DEFAULT_PORT,
    val username: String? = null,
    val password: String? = null,
) {
    val authEnabled: Boolean
        get() = !username.isNullOrEmpty() && !password.isNullOrEmpty()

    companion object {
        const val DEFAULT_PORT = 8080
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
    }
}
