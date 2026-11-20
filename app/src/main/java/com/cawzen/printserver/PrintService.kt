package com.cawzen.printserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cawzen.printserver.ipp.ClientRegistry
import com.cawzen.printserver.ipp.IppServer
import com.cawzen.printserver.ipp.JobStore
import com.cawzen.printserver.ipp.RawServer
import com.cawzen.printserver.mdns.MdnsAdvertiser
import com.cawzen.printserver.mdns.MdnsResponder
import com.cawzen.printserver.usb.UsbPrinter
import java.io.File

/**
 * 前台服务：IPP 服务器 + RAW 端口 + mDNS 广播 + 打印队列都跑在这里。
 *
 * 为什么必须是前台服务：手机熄屏后普通后台进程会被冻结，局域网里的客户端
 * 会看到「连接被拒」；而打印是「别人来敲我」的被动服务，不能靠用户保持屏幕亮。
 *
 * 前台服务类型选了 `connectedDevice`：本服务存在的意义就是驱动一个 USB 连接的
 * 打印机，这正是该类型的定义。Android 14 起这个类型要求启动时已持有 USB 权限，
 * 否则抛异常 —— 所以启动顺序是「先授权，再起服务」，并且留了 `specialUse` 的退路。
 *
 * 协议广播是**可重配**的（[reloadProtocols]）：AirPrint / Mopria / RAW 三个开关
 * 在界面上随时可能被切换，而服务不能为了一个开关重启 —— 重启会丢掉正在排队的
 * 作业。所以每个协议都做成「可单独启停」的组件。
 */
class PrintService : Service() {

    companion object {
        private const val TAG = "WPrint/Service"
        private const val CHANNEL_ID = "wprint_server"
        private const val NOTIF_ID = 0x5750

        const val ACTION_START = "com.cawzen.printserver.action.START"
        const val ACTION_STOP = "com.cawzen.printserver.action.STOP"
        const val ACTION_RELOAD = "com.cawzen.printserver.action.RELOAD"

        /** 界面直接读这个（同进程）。null 表示服务没起来。 */
        @Volatile
        var instance: PrintService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, PrintService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            // 服务没起来时不要用 startService 去「停」它 —— 那会先把服务创建出来
            // （连带一条常驻通知）再立刻销毁，看起来像闪了一下。
            if (instance == null) return
            context.startService(
                Intent(context, PrintService::class.java).setAction(ACTION_STOP)
            )
        }

        /** 协议开关变了：不重启服务，只重配广播与端口。 */
        fun reload(context: Context) {
            if (instance == null) return
            runCatching {
                context.startService(
                    Intent(context, PrintService::class.java).setAction(ACTION_RELOAD)
                )
            }
        }
    }

    private var jobs: JobStore? = null
    private var server: IppServer? = null
    private var raw: RawServer? = null

    /** AirPrint 子类型广播（自实现 responder）。 */
    private var mdns: MdnsResponder? = null

    /** 主服务 `_ipp._tcp`（系统 NsdManager）。 */
    private var mdnsLegacy: MdnsAdvertiser? = null

    @Volatile
    private var mopriaOk = false

    @Volatile
    private var lastMdnsError = ""

    /**
     * 当前已经注册出去的实例名。
     *
     * mDNS 的服务名是**注册那一刻**写进 PTR/SRV 记录的，改配置字符串不会让它变 ——
     * 必须注销再注册。这里记住"实际发出去的名字"，[applyProtocolsSync] 才能分辨
     * 「没事发生」和「用户刚改了名」。
     */
    @Volatile
    private var liveInstance = ""

    @Volatile
    var lastEvent: String = "启动中"
        private set

    /**
     * 让网卡在后台不进省电。
     *
     * 实测（本机 ColorOS / Android 14）：App 退到后台约 20 秒后，**8631 与 9101
     * 的监听 socket 依然存在、进程也还在 S 状态**，但从局域网连过来一律超时；
     * 同一时刻 adb 的 34921 却是通的 —— 说明不是整机断网，是这颗 Wi-Fi 在
     * 后台进了省电，只把「没人用的那条连接」的入站包丢了。
     *
     * 加入电池优化白名单（`cmd deviceidle whitelist +`）**不能**解决，
     * 所以问题不在 Doze/App Standby，而在射频层。
     *
     * 这里保两把锁：
     * - `WIFI_MODE_FULL_HIGH_PERF` 让 Wi-Fi 保持高性能，不在后台降功耗；
     * - `PARTIAL_WAKE_LOCK` 让 CPU 不睡，否则 accept() 拿不到调度。
     *
     * 代价是费电，但这是「手机当打印网关」的必要代价：共享开着的时候，
     * 它本来就得随时能收作业。共享关掉（[onDestroy]）立刻释放。
     */
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun acquireKeepAlive() {
        runCatching {
            val wm = getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "WPrint:gateway",
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "WifiLock 获取失败：${it.message}") }
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "WPrint:gateway").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "WakeLock 获取失败：${it.message}") }
    }

    private fun releaseKeepAlive() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wifiLock = null
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        startForegroundCompat("正在启动共享…")
        acquireKeepAlive()

        val store = JobStore(
            context = this,
            optionsProvider = { PrintOptions.load(this) },
            onUpdate = { msg ->
                lastEvent = msg
                updateNotification(msg)
                notifyJobEvent(msg)
            },
        )
        val ipp = IppServer(
            context = this,
            jobs = store,
            optionsProvider = { PrintOptions.load(this) },
            onLog = { Log.i(TAG, it) },
        )
        jobs = store
        server = ipp

        if (!ipp.start(IppServer.DEFAULT_PORT)) {
            lastEvent = "IPP 端口绑定失败"
            updateNotification(lastEvent)
            return
        }

        // 记住「用户希望共享在跑」，进程被杀后靠它恢复。
        AppSettings.update(this) { it.copy(shareEnabled = true) }

        Thread({ applyProtocolsSync() }, "protocol-setup").start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AppSettings.update(this) { it.copy(shareEnabled = false) }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> Thread({ applyProtocolsSync() }, "protocol-reload").start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { mdns?.stop() }
        runCatching { mdnsLegacy?.stop() }
        runCatching { raw?.stop() }
        runCatching { server?.stop() }
        runCatching { jobs?.shutdown() }
        releaseKeepAlive()
        mdns = null
        mdnsLegacy = null
        raw = null
        server = null
        jobs = null
        instance = null
        liveInstance = ""
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ── 协议编排 ────────────────────────────────────────────────────────────

    /**
     * 按设置启动/停止三个广播与接入组件。
     *
     * 三条必须遵守的顺序约束（都是踩过的坑）：
     *
     * 1. **主服务 `_ipp._tcp` 交给系统 `NsdManager`**。Android 的 bips、Windows 的
     *    功能发现都是向系统 `mdnsd` 要结果的，`mdnsd` 只答自己注册过的服务。
     *    把主服务搬去自写 responder，报文再合规也搜不到。
     * 2. **`_universal._sub` 子类型只能自己发**。`NsdManager` 没有子类型 API，
     *    iOS 的 AirPrint 入口就是这一个 PTR，缺了它 iPhone 列表里永远没有这台机器。
     * 3. **`NsdManager` 注册失败时，responder 要一肩挑**（`subtypeOnly=false`），
     *    否则两边都没人管主服务，Android/Windows 与 iOS 会同时搜不到。
     */
    @Synchronized
    private fun applyProtocolsSync() {
        val ipp = server ?: return
        val cfg = AppSettings.load(this)
        if (!ipp.running) return

        // 实例名变了：两套广播都要重发。
        //
        // 为什么必须注销重注册：服务名是写进 PTR/SRV 记录里的，改设置只是改了
        // 一个字符串，mDNS 那边毫无感知。不重注册的话，用户改完名发现"没反应"，
        // 只有等旧记录 TTL 到期（我们设的是 450 秒）才可能变成新名字 ——
        // 而 NsdManager 那条记录压根不会变。
        val name = cfg.effectivePrinterName()
        if (liveInstance != name) {
            val hadRegistration = mdnsLegacy != null || mdns != null
            runCatching { mdnsLegacy?.stop() }
            mdnsLegacy = null
            mopriaOk = false
            runCatching { mdns?.stop() }
            mdns = null
            liveInstance = name
            if (hadRegistration) {
                // unregisterService 是异步的（走 Binder 回调）。紧接着注册会撞
                // FAILURE_ALREADY_ACTIVE，然后主服务掉到 responder 上去重发 ——
                // 报文还是对的，但客户端可能同时看到新旧两条记录。等一拍更省事。
                runCatching { Thread.sleep(400) }
            }
        }

        // ① 主服务广播
        val wantMopria = cfg.effectiveMopria()
        if (!wantMopria) {
            runCatching { mdnsLegacy?.stop() }
            mdnsLegacy = null
            mopriaOk = false
        } else if (mdnsLegacy?.registeredName.isNullOrBlank()) {
            runCatching { mdnsLegacy?.stop() }
            val adv = MdnsAdvertiser(this, { ipp.localIp() }) { Log.i(TAG, it) }
            val ok = adv.register(name, ipp.port) && adv.awaitRegistered(3000)
            mopriaOk = ok
            mdnsLegacy = adv
            if (!ok) lastMdnsError = adv.lastError.ifBlank { "NsdManager 注册失败" }
            Log.i(TAG, "主服务广播 ${if (ok) "成功" else "失败：${adv.lastError}"}")
        }

        // ② AirPrint 子类型
        if (!cfg.airprint) {
            runCatching { mdns?.stop() }
            mdns = null
        } else if (mdns?.running != true) {
            val responder = MdnsResponder(
                context = this,
                port = ipp.port,
                instance = name,
                ipProvider = { ipp.localIp() },
                subtypeOnly = mopriaOk,
                onLog = { Log.i(TAG, it) },
            )
            if (responder.start()) {
                mdns = responder
                lastMdnsError = ""
            } else {
                lastMdnsError = responder.lastError
                mdns = null
            }
        }

        // ③ RAW 端口
        if (cfg.rawEnabled) {
            if (raw?.running != true) {
                val r = RawServer(this, jobs ?: return, onLog = { Log.i(TAG, it) })
                if (r.start(RawServer.DEFAULT_PORT)) raw = r
                else lastMdnsError = r.lastError
            }
        } else {
            runCatching { raw?.stop() }
            raw = null
        }

        val bits = buildList {
            add("IPP ${ipp.port}")
            if (mopriaOk) add("Mopria")
            if (mdns?.running == true) add("AirPrint")
            raw?.let { add("RAW ${it.port}") }
        }
        lastEvent = "共享中 · " + bits.joinToString(" / ")
        updateNotification(lastEvent)
    }

    fun reloadProtocols() {
        Thread({ applyProtocolsSync() }, "protocol-reload").start()
    }

    // ── 供界面查询的状态 ────────────────────────────────────────────────────

    fun statusSnapshot(): Status = Status(
        running = server?.running == true,
        ippUri = server?.printerUri() ?: "-",
        httpUri = server?.httpUri() ?: "-",
        mdnsName = mdnsLegacy?.registeredName ?: mdns?.registeredName ?: "",
        mdnsError = lastMdnsError.ifBlank { mdnsLegacy?.lastError ?: "" },
        airprint = mdns?.running == true,
        mopria = mopriaOk,
        port = server?.port ?: 0,
        rawPort = raw?.port ?: RawServer.DEFAULT_PORT,
        rawRunning = raw?.running == true,
        queued = jobs?.queuedCount() ?: 0,
        paused = jobs?.paused == true,
        clients = ClientRegistry.entries().count { it.lastSeen > 0 },
        portStatus = jobs?.lastPortStatus ?: "",
        lastEvent = lastEvent,
        lastJob = jobs?.last()?.let { JobSummary(it.id, it.name, it.state, it.reasons) },
    )

    data class Status(
        val running: Boolean,
        val ippUri: String,
        val httpUri: String,
        val mdnsName: String,
        val mdnsError: String,
        /** AirPrint 子类型是否在广播（iPhone 能看见的前提）。 */
        val airprint: Boolean,
        /** `_ipp._tcp` 是否由系统 mdnsd 注册成功（Windows / Android 能看见的前提）。 */
        val mopria: Boolean,
        val port: Int,
        val rawPort: Int,
        val rawRunning: Boolean,
        val queued: Int,
        val paused: Boolean,
        val clients: Int,
        /** 最近一次读到的 USB 端口状态（作业前顺手读的）。 */
        val portStatus: String,
        val lastEvent: String,
        val lastJob: JobSummary?,
    )

    data class JobSummary(val id: Int, val name: String, val state: Int, val reasons: String)

    // ── 供界面调用的动作 ────────────────────────────────────────────────────

    fun jobsOrNull(): JobStore? = jobs

    /** 打一张测试页（走队列，能在「任务」页看到，不插队）。 */
    fun submitTestPage(): String {
        val store = jobs ?: return "共享服务没有运行"
        val opts = PrintOptions.load(this)
        val pdf = com.cawzen.printserver.print.TestPdf.generate(
            File(cacheDir, "selftest-${System.currentTimeMillis()}.pdf"), pages = 1, options = opts,
            context = this,
        )
        return runCatching {
            val job = store.submit(
                name = "Cprinter 测试页",
                user = "本机",
                format = "application/pdf",
                kind = com.cawzen.printserver.print.DocumentSniffer.Kind.PDF,
                documentFile = pdf,
                duplex = opts.duplex,
                copies = 1,
                client = "",
            )
            "测试页已加入队列 #${job.id}"
        }.getOrElse { "测试页提交失败：${it.message}" }
    }

    /** 诊断页上的「重发 UEL」：把打印机从半截 PCL 流里拽出来。 */
    fun recoverPrinter(): String = runCatching {
        val device = UsbPrinter.findSupportedPrinter(this) ?: return "没有检测到打印机"
        if (!UsbPrinter.manager(this).hasPermission(device)) return "没有 USB 权限"
        val p = UsbPrinter.open(this, device) ?: return "无法打开打印机"
        try {
            // 按「从轻到重」试三种，全都不碰硬件：
            //   1. SOFT_RESET —— 走控制端点，能插进满缓冲
            //   2. 关句柄重开 —— 复位主机侧的 halted 标志
            //   3. UEL        —— PJL 语言层退出当前作业
            // 三种都做完仍拒收，就只剩断电重启打印机这一条路（那是打印机自己的
            // 解析器状态，USB 层够不着）。
            val sr = p.softReset()
            Thread.sleep(500)
            val reopened = p.reopen()
            Thread.sleep(300)
            val uel = p.sendUel()
            when {
                uel || reopened || sr -> "已尝试 USB 复位（SOFT_RESET=${if (sr) "成功" else "失败"}，" +
                    "重开连接=${if (reopened) "成功" else "失败"}，UEL=${if (uel) "成功" else "失败"}）。" +
                    "如果仍然打不出来，把打印机电源关掉再开。"
                else -> "复位失败：三种方式都没被接受，请断电重启打印机"
            }
        } finally {
            p.close()
        }
    }.getOrElse { "复位失败：${it.message}" }

    // ── 通知 ────────────────────────────────────────────────────────────────

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID, "共享打印服务", NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "无线共享运行状态与打印作业结果"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PrintService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_printer)
            .setContentTitle("Cprinter 共享中")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "停止共享", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun startForegroundCompat(text: String) {
        val notification = buildNotification(text)
        val types = listOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else 0,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else 0,
        ).filter { it != 0 }.ifEmpty { listOf(0) }

        for (type in types) {
            try {
                ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
                return
            } catch (t: Throwable) {
                // connectedDevice 在没有 USB 权限时会被拒；退到 specialUse。
                Log.w(TAG, "startForeground(type=$type) 失败：${t.javaClass.simpleName} ${t.message}")
            }
        }
        // 最后兜底：不带类型。低版本系统上这是正常路径。
        runCatching { startForeground(NOTIF_ID, notification) }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(NOTIF_ID, buildNotification(text)) }
    }

    /**
     * 作业有结果时补一条独立通知（可关）。
     *
     * 常驻通知是「共享在跑」，这条是「刚才那份打完了/失败了」—— 两件事，
     * 混在一起会让常驻通知的文字一直在变，用户反而看不到作业结果。
     */
    private fun notifyJobEvent(msg: String) {
        if (!AppSettings.load(this).notifyJobs) return
        val done = msg.contains("完成") || msg.contains("失败") || msg.contains("异常")
        if (!done) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_printer)
            .setContentTitle("打印作业")
            .setContentText(msg)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { nm.notify(NOTIF_ID + 1, n) }
    }
}
