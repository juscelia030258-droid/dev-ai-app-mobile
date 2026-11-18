package com.example.api_server

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

data class NetworkAddressState(
    val wifiIpAddress: String? = null,
    val interfaceName: String? = null,
    val isWifiConnected: Boolean = false,
    val allLocalIps: List<String> = emptyList()
) {
    fun buildBaseUrl(port: Int): String {
        val ip = wifiIpAddress ?: "127.0.0.1"
        return "http://$ip:$port/v1"
    }
}

class LocalNetworkDiscovery(private val context: Context) {

    private val _state = MutableStateFlow(scanLocalInterfaces())
    val state: StateFlow<NetworkAddressState> = _state.asStateFlow()

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var registeredCallback: ConnectivityManager.NetworkCallback? = null

    fun startMonitoring() {
        refreshNow()
        val cm = connectivityManager ?: return
        if (registeredCallback != null) return

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refreshNow()
            }

            override fun onLost(network: Network) {
                refreshNow()
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: android.net.LinkProperties
            ) {
                refreshNow()
            }
        }
        registeredCallback = callback

        runCatching {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
        }
    }

    fun stopMonitoring() {
        val callback = registeredCallback ?: return
        runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
        registeredCallback = null
    }

    fun refreshNow() {
        _state.value = scanLocalInterfaces()
    }

    private fun scanLocalInterfaces(): NetworkAddressState {
        val candidateIps = mutableListOf<Pair<String, String>>() // iface -> ip
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        candidateIps += (iface.name to host)
                    }
                }
            }
        }

        // Prefer wlan0 / wifi interfaces over cellular rmnet / tun
        val preferred = candidateIps.firstOrNull { (iface, ip) ->
            iface.startsWith("wlan", ignoreCase = true) ||
                iface.startsWith("ap", ignoreCase = true) ||
                iface.startsWith("eth", ignoreCase = true) ||
                ip.startsWith("192.168.") ||
                ip.startsWith("10.")
        } ?: candidateIps.firstOrNull()

        val activeNetwork = connectivityManager?.activeNetwork
        val caps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val hasWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true ||
            preferred?.first?.startsWith("wlan", ignoreCase = true) == true

        return NetworkAddressState(
            wifiIpAddress = preferred?.second,
            interfaceName = preferred?.first,
            isWifiConnected = hasWifi && preferred != null,
            allLocalIps = candidateIps.map { "${it.first}: ${it.second}" }
        )
    }

    companion object {
        fun isPrivateLanAddress(remoteAddress: String?): Boolean {
            if (remoteAddress.isNullOrBlank()) return false
            var clean = remoteAddress.trim().removePrefix("/")
            if (clean.startsWith("[")) {
                clean = clean.substringAfter("[").substringBefore("]")
            } else if (clean.count { it == ':' } == 1) {
                clean = clean.substringBefore(":")
            }
            clean = clean.substringBefore("%")
            if (clean.startsWith("::ffff:", ignoreCase = true)) {
                clean = clean.substring(7)
            }
            if (clean == "127.0.0.1" || clean == "::1" || clean == "0:0:0:0:0:0:0:1" || clean.equals("localhost", ignoreCase = true)) {
                return true
            }
            if (clean.startsWith("192.168.") || clean.startsWith("10.")) return true
            if (clean.startsWith("172.")) {
                val secondOctet = clean.split(".").getOrNull(1)?.toIntOrNull() ?: return false
                return secondOctet in 16..31
            }
            return clean.startsWith("fe80:", ignoreCase = true) ||
                clean.startsWith("fc", ignoreCase = true) ||
                clean.startsWith("fd", ignoreCase = true)
        }
    }
}
