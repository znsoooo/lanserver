package com.example.lanserver

import java.net.Inet4Address
import java.net.NetworkInterface

object IpUtils {

    /** 返回本机首个可用的 IPv4 地址（排除回环与链路本地），失败回退 127.0.0.1。 */
    fun getIp(): String {
        return try {
            val en = NetworkInterface.getNetworkInterfaces()
            val candidates = mutableListOf<String>()
            while (en.hasMoreElements()) {
                val nif = en.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        addr.hostAddress?.let { candidates.add(it) }
                    }
                }
            }
            candidates.firstOrNull() ?: "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }
}
