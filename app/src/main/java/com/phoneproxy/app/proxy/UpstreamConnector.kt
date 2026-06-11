package com.phoneproxy.app.proxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Opens upstream (internet-bound) connections for the proxy and makes sure they
 * travel through the phone's VPN.
 *
 * Android binds each app's traffic to a single network by UID, so a proxy whose
 * app is excluded from the VPN (the only way to keep it reachable from the
 * tethered PC on some VPN clients) would otherwise send its upstream traffic
 * over the physical network, bypassing the VPN. To prevent that, every upstream
 * socket is explicitly bound to the VPN [Network] via [Network.bindSocket], and
 * DNS is resolved through the same network so lookups also stay in the tunnel.
 *
 * When no VPN is active the connector falls back to the default network, so the
 * proxy keeps working with the VPN turned off.
 */
class UpstreamConnector(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    var vpnNetwork: Network? = null
        private set

    val vpnActive: Boolean
        get() = vpnNetwork != null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            vpnNetwork = network
            Log.d(TAG, "VPN network available")
        }

        override fun onLost(network: Network) {
            if (vpnNetwork == network) {
                vpnNetwork = null
                Log.d(TAG, "VPN network lost")
            }
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.w(TAG, "failed to register VPN network callback", e)
        }
        vpnNetwork = currentVpnNetwork()
    }

    fun stop() {
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
        vpnNetwork = null
    }

    /**
     * Connects to [host]:[port] through the VPN when one is active. Throws
     * [IOException] on failure (including if the VPN drops while connecting),
     * acting as a kill switch rather than leaking traffic outside the tunnel.
     */
    fun connect(host: String, port: Int, timeoutMs: Int): Socket {
        val network = vpnNetwork
        val address = resolve(host, network)
        val socket = Socket()
        if (network != null) {
            network.bindSocket(socket)
        }
        try {
            socket.connect(InetSocketAddress(address, port), timeoutMs)
        } catch (e: IOException) {
            Relay.closeQuietly(socket)
            throw e
        }
        return socket
    }

    private fun resolve(host: String, network: Network?): InetAddress {
        val resolved = if (network != null) {
            network.getAllByName(host)
        } else {
            InetAddress.getAllByName(host)
        }
        if (resolved.isEmpty()) throw IOException("Unable to resolve $host")
        return resolved[0]
    }

    private fun currentVpnNetwork(): Network? {
        return connectivityManager.allNetworks.firstOrNull { network ->
            val caps = connectivityManager.getNetworkCapabilities(network)
            caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    companion object {
        private const val TAG = "UpstreamConnector"
    }
}
