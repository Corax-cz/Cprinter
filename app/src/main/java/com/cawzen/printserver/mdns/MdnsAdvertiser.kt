package com.cawzen.printserver.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * mDNS 广播的**降级路径**（Stage 3 的实现，现已被 [MdnsResponder] 取代）。
 *
 * 保留它只有一个理由：万一 5353 端口被别的进程独占、[MdnsResponder] 起不来，
 * 至少还能让 macOS / Linux / Windows 的「附近打印机」看见这台机器（手填 IPP URL
 * 也照旧可用）。代价是它**不支持子类型**，所以 AirPrint 一定不生效 ——
 * `NsdManager` 没有子类型 API，这是死路，不是配置问题。
 *
 * 用系统 `NsdManager` 而不是 JmDNS：JmDNS 要额外依赖 + MulticastLock，
 * 而 NsdManager 是系统服务，不占额外内存。
 */
class MdnsAdvertiser(
    private val context: Context,
    private val ipProvider: () -> String,
    private val onLog: (String) -> Unit = {},
) {

    companion object {
        private const val TAG = "WPrint/Mdns"
        private const val SERVICE_TYPE = "_ipp._tcp"
    }

    private var manager: NsdManager? = null
    private var listener: NsdManager.RegistrationListener? = null

    @Volatile
    var registeredName: String = ""
        private set

    @Volatile
    var lastError: String = ""
        private set

    /**
     * 注册是异步的，但**调用方依赖「到底注册成功没有」来决定主服务的归属**：
     * 成功了主服务归 mdnsd，自写 responder 只补子类型；失败了就得由 responder
     * 一肩挑。所以这里留一个同步闸门，等一次回调。
     */
    @Volatile
    private var gate = java.util.concurrent.CountDownLatch(1)

    @Volatile
    private var registered = false

    fun register(name: String, port: Int): Boolean {
        stop()
        gate = java.util.concurrent.CountDownLatch(1)
        registered = false
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            lastError = "系统没有 NSD 服务"
            log(lastError)
            return false
        }
        manager = nsd

        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            this.port = port
            buildTxt(port).forEach { (k, v) -> runCatching { setAttribute(k, v) } }
        }

        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredName = info.serviceName
                registered = true
                gate.countDown()
                log("mDNS 已注册：${info.serviceName} $SERVICE_TYPE 端口 $port")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                lastError = "注册失败 errorCode=$errorCode（${nsdErrorName(errorCode)}）"
                registered = false
                gate.countDown()
                log(lastError)
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                log("mDNS 已注销：${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                log("mDNS 注销失败 errorCode=$errorCode")
            }
        }
        listener = l
        return try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            true
        } catch (t: Throwable) {
            // 常见：FAILURE_ALREADY_ACTIVE（上一次注销还没完成，Android 需要几百毫秒）。
            lastError = "registerService 抛异常：${t.javaClass.simpleName} ${t.message}"
            log(lastError)
            false
        }
    }

    /**
     * 等注册回调落地。返回**是否真的注册成功**。
     *
     * 超时按失败处理：宁可让 responder 走全功能模式（至少 iOS 能用），
     * 也不要卡在「以为 mdnsd 管了主服务、结果没人管」的半空中。
     */
    fun awaitRegistered(timeoutMs: Long = 3000): Boolean {
        return try {
            gate.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            registered
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        runCatching { manager?.unregisterService(l) }
            .onFailure { Log.w(TAG, "unregister 失败：${it.message}") }
        registeredName = ""
    }

    /**
     * IPP 服务在 Bonjour 里的标准 TXT 键（Apple 的 Bonjour Printing 规范）。
     *
     * `rp`（资源路径）必须与 IPP 路由一致，否则客户端会把作业 POST 到 `/` ——
     * 我们的路由接受 `/` 只是兜底，不是契约。
     *
     * 内容与 [MdnsResponder] 共用 [AirPrintTxt]，避免两条广播路径说两套话
     * （TXT 声明 URF 而 IPP 不认，客户端就会送 URF 然后每个作业都失败）。
     */
    private fun buildTxt(port: Int): Map<String, String> =
        AirPrintTxt.build(context, ipProvider(), port)

    private fun log(msg: String) {
        Log.i(TAG, msg)
        onLog(msg)
    }

    private fun nsdErrorName(code: Int): String = when (code) {
        NsdManager.FAILURE_ALREADY_ACTIVE -> "ALREADY_ACTIVE"
        NsdManager.FAILURE_INTERNAL_ERROR -> "INTERNAL_ERROR"
        NsdManager.FAILURE_MAX_LIMIT -> "MAX_LIMIT"
        else -> "UNKNOWN"
    }
}
