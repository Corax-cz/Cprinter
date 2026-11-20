package com.cawzen.printserver.ipp

import java.util.concurrent.ConcurrentHashMap

/**
 * 「谁在用这台共享打印机」的唯一来源。
 *
 * 为什么要单独一个登记表：IPP 是**别人来敲我**的协议，没有登录、没有会话。
 * 界面上「已连接设备」这一栏如果靠猜（比如数 mDNS 查询次数），就会出现
 * 「显示 3 台、实际只有 1 台在打印」这种谁也解释不清的状态。这里记的是
 * 真实到达过 HTTP 层的事实：IP、UA、首次/最近时间、请求数与作业数。
 *
 * 三条使用约定：
 *
 * 1. 每个请求进来先 [setThreadClient]，处理完在 finally 里 [clearThreadClient]。
 *    作业由哪个 IP 提交的，就靠这个 ThreadLocal 传给 [JobStore]。
 * 2. [touch] 只记事实，不做判断；能不能接入是 [IppServer] 的事。
 * 3. 表最多保留 [MAX_ENTRIES] 条，按最近活跃淘汰 —— 局域网里扫描器很多，
 *    不设上限会被塞满。
 */
object ClientRegistry {

    private const val MAX_ENTRIES = 24

    data class Entry(
        val ip: String,
        /** 原始 User-Agent，界面只做粗分类显示。 */
        @Volatile var userAgent: String = "",
        @Volatile var firstSeen: Long = 0L,
        @Volatile var lastSeen: Long = 0L,
        @Volatile var requests: Int = 0,
        @Volatile var jobs: Int = 0,
        /** 被白名单拒绝过（界面据此提示「有设备请求接入」）。 */
        @Volatile var blocked: Boolean = false,
    )

    private val map = ConcurrentHashMap<String, Entry>()

    private val threadClient = ThreadLocal<String?>()
    private val threadAgent = ThreadLocal<String?>()

    fun setThreadClient(ip: String?, ua: String?) {
        threadClient.set(ip)
        threadAgent.set(ua)
    }

    fun clearThreadClient() {
        threadClient.remove()
        threadAgent.remove()
    }

    /** 当前请求的提交者 IP；非请求线程（测试页、本机任务）返回空串。 */
    fun currentClient(): String = threadClient.get() ?: ""

    fun touch(ip: String, ua: String) {
        if (ip.isBlank()) return
        val now = System.currentTimeMillis()
        val e = map.computeIfAbsent(ip) { Entry(it, firstSeen = now, lastSeen = now) }
        e.lastSeen = now
        e.requests += 1
        if (ua.isNotBlank() && ua != "-") e.userAgent = ua
        if (map.size > MAX_ENTRIES) prune()
    }

    fun recordJob(ip: String) {
        if (ip.isBlank()) return
        map[ip]?.let { it.jobs += 1; it.lastSeen = System.currentTimeMillis() }
    }

    fun recordBlocked(ip: String, ua: String) {
        if (ip.isBlank()) return
        val now = System.currentTimeMillis()
        val e = map.computeIfAbsent(ip) { Entry(it, firstSeen = now) }
        e.blocked = true
        e.lastSeen = now
        if (ua.isNotBlank()) e.userAgent = ua
    }

    /** 当前已知客户端，最近活跃在前。 */
    fun entries(): List<Entry> = map.values.sortedByDescending { it.lastSeen }

    fun forget(ip: String) {
        map.remove(ip)
    }

    fun clear() = map.clear()

    private fun prune() {
        val keep = map.values.sortedByDescending { it.lastSeen }.take(MAX_ENTRIES)
        val keepIps = keep.map { it.ip }.toSet()
        map.keys.retainAll(keepIps)
    }

    /**
     * 把 UA 粗分类成人能读的设备名。
     *
     * 只认几个确定会出现的串：AirPrint 走的是 iOS/macOS 自带的 IPP 客户端，
     * Windows 走 `Microsoft-Windows` / `IPP Client`，Linux/CUPS 走 `CUPS`。
     * 认不出来就老实显示「未知设备」，不要硬猜 —— 猜错会让用户去排查
     * 一台根本不存在的设备。
     */
    fun deviceLabel(ua: String): String {
        val s = ua.lowercase()
        return when {
            s.isBlank() || s == "-" -> "未知设备"
            s.contains("iphone") -> "iPhone"
            s.contains("ipad") -> "iPad"
            s.contains("macintosh") || s.contains("mac os") -> "Mac"
            s.contains("windows") -> "Windows 电脑"
            s.contains("cups") -> "CUPS 客户端"
            s.contains("android") -> "Android 设备"
            s.contains("linux") -> "Linux 设备"
            s.contains("ipp") -> "IPP 客户端"
            else -> "未知设备"
        }
    }
}
