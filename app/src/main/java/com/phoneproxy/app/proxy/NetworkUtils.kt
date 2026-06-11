package com.phoneproxy.app.proxy

import java.net.Inet4Address
import java.net.NetworkInterface

/** A reachable IPv4 address the proxy is listening on, with its interface name. */
data class LocalAddress(val interfaceName: String, val ip: String, val isLikelyTether: Boolean)

object NetworkUtils {

    // Interface name prefixes commonly used by Android for USB/Wi-Fi tethering.
    private val TETHER_PREFIXES = listOf("rndis", "usb", "ap", "swlan", "wlan1")

    /**
     * Enumerates non-loopback IPv4 addresses. Addresses on interfaces that look
     * like tethering interfaces (USB modem / Wi-Fi hotspot) are flagged and
     * sorted first so the UI can highlight the address the PC should use.
     */
    fun localAddresses(): List<LocalAddress> {
        val result = ArrayList<LocalAddress>()
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            val name = nif.name.lowercase()
            val likelyTether = TETHER_PREFIXES.any { name.startsWith(it) }
            for (addr in nif.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    result.add(LocalAddress(nif.name, addr.hostAddress ?: continue, likelyTether))
                }
            }
        }
        return result.sortedByDescending { it.isLikelyTether }
    }
}
