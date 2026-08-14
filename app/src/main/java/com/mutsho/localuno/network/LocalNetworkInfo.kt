package com.mutsho.localuno.network

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Best-effort local IPv4 address, for showing the host so a player can type it in manually
 * when mDNS/NSD discovery doesn't work (common on guest/corporate Wi-Fi with client isolation).
 * NetworkInterface enumeration needs no runtime permission, unlike the deprecated WifiManager APIs.
 *
 * Link-local addresses (169.254.x.x - self-assigned before DHCP finishes) are excluded since
 * they're not reachable by anyone else. When multiple interfaces are up (e.g. a VPN alongside
 * Wi-Fi), a Wi-Fi-named one is preferred so we don't hand out an address only the phone itself
 * can reach.
 */
fun getLocalIpAddress(): String? {
    return try {
        val candidates = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { iface ->
                iface.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    .map { iface.name to it }
            }
            .toList()

        candidates
            .sortedByDescending { (name, _) -> name.startsWith("wlan") || name.startsWith("ap") }
            .firstOrNull()
            ?.second
            ?.hostAddress
    } catch (_: Exception) {
        null
    }
}
