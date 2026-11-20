package com.cawzen.printserver.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * 用系统 [NsdManager] 自己走一遍「发现」流程。
 *
 * 存在的理由：这台设备上「添加打印机」列表是空的，但空列表有两种完全不同的成因，
 * 光看列表分不出来 ——
 *
 *   ① 服务没进 `mdnsd`：那就谁也发现不了，属于我们自己的问题；
 *   ② 服务在 `mdnsd` 里，但 `bips` 的发现会话没起来：那是系统 UI 的问题。
 *
 * 这个探针回答的正是 ①：如果 App **自己**用 `NsdManager.discoverServices` 能看见
 * 刚注册的服务，说明 `mdnsd` 那条路是通的，空列表与广播无关；连自己也看不见，
 * 那就是注册/发现这一层根本没工作。
 *
 * 注意它只能证明「系统发现链路是否正常」，不能替代客户端验收 —— `bips` 用的是
 * 同一套 `NsdManager`，所以探针通过而 `bips` 仍为空，结论就只能是 `bips` 自己的
 * 发现会话没启动（`dumpsys print` 里 `has_discovery_session=false`）。
 */
object DiscoveryProbe {

    private const val TYPE = "_ipp._tcp"

    fun run(context: Context, timeoutMs: Long = 6000): String {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: return "系统没有 NSD 服务，无法发现。"

        val found = LinkedHashMap<String, NsdServiceInfo>()
        val events = mutableListOf<String>()
        // 用单元素数组而不是 @Volatile var：Kotlin 不允许局部变量加 @Volatile，
        // 而这里需要从回调线程写、从调用线程读。
        val startError = arrayOf("")

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                synchronized(events) { events += "发现会话已启动：$serviceType" }
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(found) { found[info.serviceName] = info }
                synchronized(events) { events += "发现 ${info.serviceName}（${info.serviceType}）" }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(events) { events += "丢失 ${info.serviceName}" }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                synchronized(events) { events += "发现会话已停止" }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                startError[0] = "onStartDiscoveryFailed errorCode=$errorCode"
                synchronized(events) { events += startError[0] }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                synchronized(events) { events += "onStopDiscoveryFailed errorCode=$errorCode" }
            }
        }

        val launched = runCatching {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }
        if (launched.isFailure) {
            return "discoverServices 抛异常：${launched.exceptionOrNull()?.let { "${it.javaClass.simpleName} ${it.message}" }}"
        }

        // 发现是异步的，给它足够时间把应答收全。这里阻塞的是 IO 线程，不是主线程。
        Thread.sleep(timeoutMs)
        runCatching { nsd.stopServiceDiscovery(listener) }
        Thread.sleep(300)

        val sb = StringBuilder()
        sb.appendLine("用 NsdManager 查 $TYPE（等待 ${timeoutMs}ms）")
        sb.appendLine()
        synchronized(events) { events.forEach { sb.appendLine("· $it") } }
        sb.appendLine()

        val list = synchronized(found) { found.values.toList() }
        if (list.isEmpty()) {
            sb.appendLine("结论：**一条也没发现**。")
            sb.appendLine("这说明系统 mdnsd 的发现链路本身不通 —— 问题在广播/注册层，")
            sb.appendLine("与「添加打印机」界面的 UI 无关。")
            if (startError[0].isNotEmpty()) sb.appendLine("启动阶段的错误：${startError[0]}")
        } else {
            sb.appendLine("结论：发现 ${list.size} 个服务 ——")
            list.forEach { info ->
                sb.appendLine("  ${info.serviceName}  端口 ${info.port}")
                val attrs = info.attributes?.entries?.joinToString(" ") { (k, v) ->
                    "$k=${runCatching { String(v ?: ByteArray(0)) }.getOrDefault("?")}"
                }
                if (!attrs.isNullOrBlank()) sb.appendLine("    TXT: $attrs")
            }
            sb.appendLine()
            sb.appendLine("系统发现链路正常。若「添加打印机」列表仍为空，")
            sb.appendLine("原因在 bips 的发现会话没启动（dumpsys print → has_discovery_session）。")
        }
        return sb.toString().trim()
    }
}
