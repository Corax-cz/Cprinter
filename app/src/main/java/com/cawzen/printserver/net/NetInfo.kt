package com.cawzen.printserver.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 「服务对外用哪个地址」的唯一答案来源。
 *
 * 为什么不能只用「第一个站点内网 IPv4」：手机同时挂着蜂窝网（`rmnet`）、
 * 可能还有热点（`ap0`）或 VPN（`tun0`），遍历顺序完全不受控。挑错了，IPP 响应里的
 * `printer-uri-supported` 就是个别人连不上的地址；AirPrint 尤其在意这点——
 * iOS 会拿 mDNS 解析出的地址去比对 IPP 回的 URI，不一致时打印机会从列表里消失。
 *
 * 所以这里以系统的 `ConnectivityManager` 为准：先找 WiFi（其次以太网），
 * 从它的 `LinkProperties` 直接拿接口名与 IPv4。遍历网络接口只作兜底。
 */
object NetInfo {

    private const val TAG = "WPrint/Net"

    data class Iface(val name: String, val ip: String)

    /** 当前默认路由所在的 WiFi/以太网接口；拿不到返回 null。 */
    fun wifi(context: Context): Iface? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return fallback()
        val nets = runCatching { cm.allNetworks }.getOrDefault(emptyArray())
        // 优先 wifi，其次以太网（部分机型走 USB 网卡）。
        for (transport in listOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_ETHERNET)) {
            for (n in nets) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(transport)) continue
                // VPN 必须跳过：Android 给 VPN 网络的 capabilities 里会同时带上
                // 底层传输类型（WIFI），也带着底层接口上的地址 —— 实测开着
                // Tailscale/Clash 时这里会选中 tun0 和 100.64/10 的地址，
                // 于是 IPP 对外宣告一个局域网里没人能连的 URL，AirPrint 直接消失。
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue

                val lp = cm.getLinkProperties(n) ?: continue
                val name = lp.interfaceName ?: continue
                val v4s = lp.linkAddresses
                    .map { it.address }
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                // 站点内网地址优先（192.168/10/172.16）；实在没有才退而求其次，
                // 因为客户端能不能连到，取决于地址是不是局域网内可路由的。
                val v4 = v4s.firstOrNull { it.isSiteLocalAddress } ?: v4s.firstOrNull() ?: continue
                return Iface(name, v4.hostAddress ?: continue)
            }
        }
        return fallback()
    }

    fun ip(context: Context): String = wifi(context)?.ip ?: "127.0.0.1"

    /** 按名字取网络接口；mDNS 绑定组播成员时要它。 */
    fun interfaceByName(name: String?): NetworkInterface? {
        if (name.isNullOrBlank()) return null
        return runCatching { NetworkInterface.getByName(name) }.getOrNull()
    }

    /**
     * 兜底：扫所有接口，取站点内网的 IPv4，`wlan*`/`eth*` 优先。
     *
     * 只在 ConnectivityManager 给不出结果时用（例如系统服务异常），
     * 所以顺序尽量按常见命名排，而不是信遍历顺序。
     */
    private fun fallback(): Iface? {
        val all = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
        }.getOrDefault(emptyList())

        val candidates = all.mapNotNull { nif ->
            val v4 = nif.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress && !it.isLinkLocalAddress }
                ?: return@mapNotNull null
            Iface(nif.name, v4.hostAddress ?: return@mapNotNull null)
        }
        if (candidates.isEmpty()) return null
        val preferred = candidates.firstOrNull {
            it.name.startsWith("wlan") || it.name.startsWith("eth") || it.name.startsWith("ap")
        }
        val picked = preferred ?: candidates.first()
        Log.i(TAG, "兜底选网卡 ${picked.name} → ${picked.ip}（候选：${candidates.joinToString { "${it.name}=${it.ip}" }}）")
        return picked
    }
}
