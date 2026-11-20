package com.cawzen.printserver.mdns

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.cawzen.printserver.net.NetInfo
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * 手写的 mDNS 响应器（RFC 6762 / 6763），专为 AirPrint 服务。
 *
 * ## 为什么不用系统 NsdManager
 *
 * AirPrint 的发现条件是 PTR `_universal._sub._ipp._tcp.local` —— **一个子类型**。
 * iOS 只把带这个子类型的服务列进「附近的打印机」，而 Android 的 `NsdManager`
 * 从 API 16 到现在都没有任何子类型 API（`NsdServiceInfo` 只有 name/type/port/TXT）。
 * 结果就是：用 NsdManager 广播的 `_ipp._tcp` 在 macOS 的 `ippfind` 里看得见，
 * 在 iPhone 的打印面板里永远看不见。这不是 TXT 写得不对，是协议入口就不对。
 *
 * 所以这里自己发 DNS 报文：只做「响应器」该做的事 —— 收查询、答 PTR/SRV/TXT/A、
 * 启动时主动公告三次、退出时发 TTL=0 的告别。不做缓存、不做去重、不做冲突探测，
 * 那些是给多播 DNS 浏览器用的，服务器端不需要。
 *
 * ## 一个容易踩的坑
 *
 * 响应必须**从 5353 端口发出**（所以我们绑定 5353 而不是随便一个端口），
 * 否则标准客户端会把响应当成伪造包丢掉。绑定前开 SO_REUSEADDR，这样和系统的
 * `mdnsd` 共存 —— 两个进程同时听 5353 是合法且常见的（Avahi 也这么干）。
 */
class MdnsResponder(
    private val context: Context,
    private val port: Int,
    private val instance: String = AirPrintTxt.INSTANCE,
    private val ipProvider: () -> String,
    /**
     * 只广播 `_universal._sub._ipp._tcp` 一条子类型 PTR，主服务
     * （`_ipp._tcp` 的 PTR/SRV/TXT/A）交给系统 `NsdManager`。
     *
     * 为什么要分：`NsdManager` 的报文由系统 `mdnsd` 生成，Windows 与 Android
     * 的「添加打印机」认得；但它**没有子类型 API**，所以 iOS 的 AirPrint
     * 入口必须由我们自己补。两边各管一段，谁也不越界。
     */
    private val subtypeOnly: Boolean = false,
    private val onLog: (String) -> Unit = {},
) {

    companion object {
        private const val TAG = "WPrint/Mdns"
        private const val GROUP = "224.0.0.251"
        private const val MCAST_PORT = 5353
        private const val MAX_PACKET = 1400

        private const val T_A = 1
        private const val T_PTR = 12
        private const val T_TXT = 16
        private const val T_SRV = 33
        private const val T_ANY = 255

        // RFC 6762 §10 建议服务/文本记录用 75 分钟（4500），但那意味着改了 TXT
        // 之后一小时多才能全网生效 —— 调试期太难受，而且家里这点流量毫无意义。
        // 450 秒（7.5 分钟）既错得开，又能让 iPhone 在一次重连内拿到新内容。
        private const val TTL_SERVICE = 450
        private const val TTL_HOST = 120          // 主机地址短一些，换网时别人更快重查
        private const val CACHE_FLUSH = 0x8000    // 唯一记录（SRV/TXT/A）要置这个位
    }

    // 名字都以标签数组表示，交给 DnsWriter 做压缩。用字符串拼接会被 "a.b" 与
    // "a\.b" 这种转义问题咬到，虽然我们的名字很简单，但没必要留这个口子。
    private val svcName = listOf("_ipp", "_tcp", "local")
    private val subName = listOf("_universal", "_sub", "_ipp", "_tcp", "local")
    private val enumName = listOf("_services", "_dns-sd", "_udp", "local")
    private val instName get() = listOf(instance, "_ipp", "_tcp", "local")
    private val hostName get() = listOf(AirPrintTxt.hostLabel(context), "local")

    @Volatile
    var running = false
        private set

    @Volatile
    var lastError: String = ""
        private set

    /** 对外展示用的名字（成功后有值）。 */
    @Volatile
    var registeredName: String = ""
        private set

    @Volatile
    private var socket: MulticastSocket? = null
    private var rxThread: Thread? = null
    private var tickThread: Thread? = null
    private var scheduler: ScheduledExecutorService? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var boundIfaceName: String? = null

    private fun log(msg: String) {
        Log.i(TAG, msg)
        onLog(msg)
    }

    // ── 生命周期 ────────────────────────────────────────────────────────────

    fun start(): Boolean {
        if (running) return true
        lastError = ""
        val ifaceInfo = NetInfo.wifi(context)
        val nif = NetInfo.interfaceByName(ifaceInfo?.name)
        if (nif == null) {
            lastError = "没有可用的 WiFi 接口（${ifaceInfo?.name ?: "未连接"}）"
            log(lastError)
            return false
        }
        if (ipProvider() == "127.0.0.1") {
            lastError = "拿不到局域网 IP，mDNS 没有意义"
            log(lastError)
            return false
        }

        val s = MulticastSocket(null)
        try {
            s.reuseAddress = true
            s.bind(InetSocketAddress(MCAST_PORT))
            s.timeToLive = 255
            s.networkInterface = nif
            s.joinGroup(InetSocketAddress(InetAddress.getByName(GROUP), MCAST_PORT), nif)
        } catch (t: Throwable) {
            lastError = "绑定 5353 失败：${t.javaClass.simpleName} ${t.message}"
            log(lastError)
            runCatching { s.close() }
            return false
        }

        socket = s
        boundIfaceName = ifaceInfo?.name
        acquireMulticastLock()
        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "mdns-tx").apply { isDaemon = true }
        }
        running = true
        registeredName = instance

        rxThread = thread(name = "mdns-rx", isDaemon = true) { receiveLoop(s) }
        tickThread = thread(name = "mdns-tick", isDaemon = true) { tickLoop() }

        log(
            "mDNS 已启动：$instance._ipp._tcp.local → ${ipProvider()}:$port" +
                "（接口 ${ifaceInfo?.name}，含 _universal 子类型）"
        )
        announce()
        return true
    }

    fun stop() {
        if (!running && socket == null) return
        running = false
        // 告别包：TTL=0，让还在缓存里的客户端立刻把这台机器从列表里去掉。
        runCatching { announce(ttl = 0) }
        runCatching { socket?.close() }
        socket = null
        runCatching { rxThread?.interrupt() }
        runCatching { tickThread?.interrupt() }
        rxThread = null
        tickThread = null
        scheduler?.shutdownNow()
        scheduler = null
        releaseMulticastLock()
        registeredName = ""
        log("mDNS 已停止")
    }

    /**
     * 部分机型（尤其 MIUI/ColorOS）会在 WiFi 固件层丢掉组播，除非持有一个
     * MulticastLock。没有这个权限时只记一条日志 —— 有些机型不加锁也能收到，
     * 没必要因此拒绝启动整个广播。
     */
    private fun acquireMulticastLock() {
        try {
            val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return
            val lock = wm.createMulticastLock("wprint-mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
            multicastLock = lock
        } catch (t: Throwable) {
            log("MulticastLock 不可用（${t.javaClass.simpleName}）：若发现 iPhone 搜不到，检查 CHANGE_WIFI_MULTICAST_STATE 权限")
        }
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    // ── 接收 ────────────────────────────────────────────────────────────────

    private fun receiveLoop(s: MulticastSocket) {
        val buf = ByteArray(9000)
        log("mDNS 接收线程已启动（local=${s.localSocketAddress}，界面=${boundIfaceName}）")
        while (running) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                s.receive(pkt)
            } catch (t: Throwable) {
                if (!running) break
                Log.w(TAG, "receive 异常：${t.message}")
                continue
            }
            // 前若干个包打日志：排查「iPhone 搜不到」时，第一件事就是确认
            // 组播到底有没有到达进程 —— 没有到达就与协议实现无关。
            if (rxSeen < 20) {
                rxSeen++
                log("收到 UDP ${pkt.length} 字节 来自 ${pkt.address.hostAddress}:${pkt.port}")
            }
            try {
                handlePacket(pkt.data, pkt.length, pkt.address, pkt.port)
            } catch (t: Throwable) {
                Log.w(TAG, "处理查询失败：${t.message}")
            }
        }
    }

    @Volatile
    private var rxSeen = 0

    private fun handlePacket(data: ByteArray, len: Int, from: InetAddress, fromPort: Int) {
        val r = DnsReader(data, len)
        val id = r.u16() ?: return
        val flags = r.u16() ?: return
        if (flags and 0x8000 != 0) return          // 是响应，不是查询：不关心
        val qd = r.u16() ?: return
        r.u16(); r.u16(); r.u16()                  // an / ns / ar 计数（用不到）
        if (qd <= 0) return

        val answers = LinkedHashMap<String, Rec>()
        val additionals = LinkedHashMap<String, Rec>()
        val questions = ArrayList<Question>()
        var unicastWanted = fromPort != MCAST_PORT

        for (i in 0 until qd) {
            val q = r.question() ?: break
            if (q.type == 0) continue
            if (q.uni) unicastWanted = true
            questions.add(q)
            match(q, answers, additionals)
        }
        if (answers.isEmpty()) return

        // RFC 6762 §18.1：**多播**响应的 Query Identifier 必须为 0。
        // 单播（QU）响应则要回显，客户端靠它把响应配对到自己的请求。
        // 原先两种都回显了查询 ID —— 这是个硬伤，Apple 的 mDNSResponder 和
        // Windows 的 Dnscache 都会把 ID 非 0 的多播响应判为无效包直接丢掉，
        // 而这类「包收到了但不认」的症状，用任何宽松的自制客户端都复现不出来。
        val msg = encode(
            if (unicastWanted) id else 0,
            answers.values.toList(),
            additionals.values.toList(),
            questions = questions,
        )
        if (msg == null) {
            log("响应包超过 $MAX_PACKET 字节，丢弃（${answers.size} 条应答）")
            return
        }
        log(
            "应答 ${answers.size} 条 + ${additionals.size} 条附加 → " +
                if (unicastWanted) "单播 ${from.hostAddress}:$fromPort" else "组播"
        )
        if (unicastWanted) {
            sendTo(msg, from, fromPort)
        } else {
            // RFC 6762 §6：多播响应要错开 20–120ms，避免多台设备同时抢答。
            val delay = 20L + Random.nextLong(100)
            val sch = scheduler
            if (sch != null) {
                sch.schedule({ runCatching { sendMulticast(msg) } }, delay, TimeUnit.MILLISECONDS)
            } else {
                runCatching { sendMulticast(msg) }
            }
        }
    }

    /** 把一条查询命中到我们的记录上。命中什么就往 answers/additionals 里放什么。 */
    private fun match(q: Question, answers: MutableMap<String, Rec>, additionals: MutableMap<String, Rec>) {
        // DNS 名字大小写不敏感：客户端会把 PTR 里的原始大小写原样抄进查询，
        // 所以两边都折成小写再比。记录本身仍按原样发出（显示名要保留大写）。
        val name = q.name.joinToString(".").lowercase()
        fun key(labels: List<String>) = labels.joinToString(".").lowercase()
        fun put(map: MutableMap<String, Rec>, rec: Rec) {
            map.putIfAbsent("${key(rec.name)}|${rec.type}", rec)
        }
        fun withHost(recs: List<Rec>) {
            recs.forEach { put(answers, it) }
            serviceRecords().forEach { put(additionals, it) }
        }

        when {
            // AirPrint 的入口：子类型 PTR。**两种模式下都要应答** ——
            // 这是系统 NsdManager 唯一做不到的事。
            //
            // 但 subtypeOnly 时**不能附带 SRV/TXT/A**。我们的 SRV target 是
            // `wprint-xxxx.local`，而系统 mdnsd 注册的主机名是设备自己的；
            // 把我们的 SRV 塞进附加记录，客户端会拿它去查 A 记录，而我们在
            // 这个模式下不答 A —— 名字解析失败，打印机从列表里消失。
            // 只给一条 PTR，让客户端顺着名字去问系统 mdnsd 拿全套。
            q.type == T_PTR && name == key(subName) -> {
                put(answers, ptrRecord(subName))
                if (!subtypeOnly) serviceRecords().forEach { put(additionals, it) }
            }

            // 我们自己的主机名 `wprint-xxxx.local`：**两种模式下都要应答**。
            //
            // 系统 mdnsd 注册服务时用的是设备自己的主机名（`Android_xxx.local`），
            // 它根本不知道 `wprint-xxxx.local` 这个名字。可是客户端在这一轮切换
            // 之前已经缓存过指向旧名字的 SRV（TTL 450 秒），缓存没过期时它会去
            // 解析旧名字 —— 解析不到，打印机就从列表里消失，而且看起来毫无理由。
            // 这条 A 记录不跟系统冲突（名字不同），补上纯赚。
            name == key(hostName) && (q.type == T_A || q.type == T_ANY) ->
                withHost(listOfNotNull(aRecord()))

            // 以下全部属于「主服务」。subtypeOnly 时系统 mdnsd 已经在广播同一份
            // 记录，我们再说一遍只会制造重复应答，客户端要处理两份来源不一致的
            // 缓存。所以这里直接不响应。
            subtypeOnly -> Unit

            // 服务枚举：`_services._dns-sd._udp` 只回一个「我提供 _ipp._tcp」的 PTR。
            //
            // `unique = false` 是必须的。PTR 是**共享**记录，RFC 6762 §10.2 明确
            // 禁止在 PTR 上置 cache-flush 位。这里原先漏传该参数、吃了默认值 true，
            // 线上报文里这一条的 class 是 0x8001 而不是 0x0001 —— Windows 的
            // DNS 客户端与 Android 的 mdnsresponder 都会因为这条记录非法，
            // 把**整个响应包**判为无效。
            q.type == T_PTR && name == key(enumName) ->
                put(answers, Rec(enumName, T_PTR, TTL_SERVICE, unique = false) { w -> w.name(svcName) })

            // iOS / macOS 找打印机就是问这个。
            q.type == T_PTR && name == key(svcName) ->
                withHost(listOf(ptrRecord(svcName)))

            // 实例的 SRV/TXT。ANY(255) 必须一起处理 —— 部分浏览器与
            // `dns-sd -L` 之类的工具对实例直接发 ANY，只认 SRV/TXT 精确查询
            // 会让它们拿到空应答，看起来就像「这台机器不存在」。
            name == key(instName) && q.type == T_ANY ->
                withHost(listOf(srvRecord(), txtRecord()))

            name == key(instName) && (q.type == T_SRV || q.type == T_ANY) ->
                withHost(listOf(srvRecord()))

            name == key(instName) && q.type == T_TXT ->
                withHost(listOf(txtRecord()))

            // ANY 查询：按问到的名字回对应 owner 的 PTR。原先两个名字都回 svcName，
            // 客户端查子类型时会拿到一条 owner 对不上的记录，只能丢掉。
            q.type == T_ANY && name == key(svcName) ->
                withHost(listOf(ptrRecord(svcName)))

            q.type == T_ANY && name == key(subName) ->
                withHost(listOf(ptrRecord(subName)))
        }
    }

    // ── 记录 ────────────────────────────────────────────────────────────────

    private class Rec(
        val name: List<String>,
        val type: Int,
        val ttl: Int,
        /** 唯一记录（SRV/TXT/A）：响应里要置 cache-flush 位。PTR 是共享记录，不置。 */
        val unique: Boolean = true,
        val rdata: (DnsWriter) -> Unit,
    )

    private fun ptrRecord(owner: List<String>) =
        Rec(owner, T_PTR, TTL_SERVICE, unique = false) { w -> w.name(instName) }

    private fun srvRecord() = Rec(instName, T_SRV, TTL_HOST) { w ->
        w.u16(0)          // priority
        w.u16(0)          // weight
        w.u16(port)
        w.name(hostName)
    }

    private fun txtRecord() = Rec(instName, T_TXT, TTL_SERVICE) { w -> w.txt(txtPairs()) }

    private fun aRecord(): Rec? {
        val ip = runCatching { InetAddress.getByName(ipProvider()) }.getOrNull() as? Inet4Address ?: return null
        return Rec(hostName, T_A, TTL_HOST) { w -> w.bytes(ip.address) }
    }

    /** SRV + TXT + A：作为 PTR 应答的 additional，客户端就不用再问一轮。 */
    private fun serviceRecords(): List<Rec> = listOfNotNull(srvRecord(), txtRecord(), aRecord())

    private fun txtPairs(): List<String> {
        val ip = ipProvider()
        return AirPrintTxt.build(context, ip, port).map { (k, v) -> "$k=$v" }
    }

    // ── 发送 ────────────────────────────────────────────────────────────────

    /**
     * 主动公告。
     *
     * 全功能模式发完整一套（`_ipp._tcp` PTR + 子类型 PTR + SRV + TXT + A）；
     * [subtypeOnly] 模式**只发子类型 PTR 一条** —— 主服务的公告由系统 mdnsd
     * 自己发，重复公告会让客户端收到两份内容不完全相同的缓存。
     */
    private fun announce(ttl: Int? = null) {
        val recs = buildList {
            if (subtypeOnly) {
                add(ptrRecord(subName))
                // 顺带公告自己主机名的 A 记录，帮客户端在切换期刷新旧缓存。
                aRecord()?.let { add(it) }
            } else {
                add(ptrRecord(svcName))
                add(ptrRecord(subName))
                add(srvRecord())
                add(txtRecord())
                aRecord()?.let { add(it) }
            }
        }
        val msg = encode(0, recs, emptyList(), ttl) ?: return
        runCatching { sendMulticast(msg) }
    }

    private fun sendMulticast(msg: ByteArray) {
        val s = socket ?: return
        val group = InetAddress.getByName(GROUP)
        s.send(DatagramPacket(msg, msg.size, group, MCAST_PORT))
    }

    private fun sendTo(msg: ByteArray, addr: InetAddress, port: Int) {
        val s = socket ?: return
        s.send(DatagramPacket(msg, msg.size, addr, port))
    }

    /**
     * 每秒一次的守护线程：补发公告、盯 IP 变化。
     *
     * IP 变化必须处理 —— 手机换网段（回家/公司）后，缓存里还留着旧地址的 A 记录，
     * 客户端会连到一个不存在的 IP，表现是「打印机在列表里但一点就报错」。
     */
    private fun tickLoop() {
        var ticks = 0
        var lastIp = ipProvider()
        var lastIface = boundIfaceName
        while (running) {
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                break
            }
            if (!running) break
            ticks++

            if (ticks == 1 || ticks == 2) {
                announce()                        // 加上启动时那次，共三次公告
                continue
            }
            if (ticks % 10 == 0) {
                val ip = ipProvider()
                val ifaceName = NetInfo.wifi(context)?.name
                if (ip != lastIp || ifaceName != lastIface) {
                    log("网络变化：$lastIp → $ip（接口 $lastIface → $ifaceName），重新公告")
                    lastIp = ip
                    if (ifaceName != lastIface) {
                        lastIface = ifaceName
                        rebound()
                    }
                    announce()
                }
            }
            if (ticks % 300 == 0) announce()      // 5 分钟刷一次，防止缓存过期
        }
    }

    /** 换了网络接口（WiFi → 以太网）时重新绑定组播成员。 */
    private fun rebound() {
        val nif = NetInfo.interfaceByName(NetInfo.wifi(context)?.name) ?: return
        val s = socket ?: return
        runCatching {
            s.networkInterface = nif
            s.joinGroup(InetSocketAddress(InetAddress.getByName(GROUP), MCAST_PORT), nif)
        }.onFailure { log("重绑组播失败：${it.message}") }
    }

    // ── DNS 编码 ────────────────────────────────────────────────────────────

    private fun encode(
        id: Int,
        answers: List<Rec>,
        additionals: List<Rec>,
        ttlOverride: Int? = null,
        questions: List<Question> = emptyList(),
    ): ByteArray? {
        val w = DnsWriter()
        w.u16(id)
        w.u16(0x8400)                 // QR=1（响应）+ AA=1（权威）
        w.u16(questions.size)         // QDCOUNT：回显问题段
        w.u16(answers.size)
        w.u16(0)
        w.u16(additionals.size)
        // 回显问题段。RFC 6762 §18.1 允许省略（QDCOUNT=0），但 Windows 的
        // Dnscache 与部分 Android 客户端会拿「响应里的 question」和「自己发出的
        // question」做匹配，省略后它们认不出来，表现就是「有应答但不列出来」。
        // 顺带的好处：问题段先出现，后面记录的名字都能压缩指回来，报文更小。
        for (q in questions) {
            w.name(q.name)
            w.u16(q.type)
            w.u16(1)                  // QCLASS=IN
        }
        for (r in answers) w.record(r, ttlOverride)
        for (r in additionals) w.record(r, ttlOverride)
        if (w.size > MAX_PACKET) {
            // 只有 additional 撑爆时降级：应答本身更重要，先保证能被解析。
            val w2 = DnsWriter()
            w2.u16(id); w2.u16(0x8400)
            w2.u16(questions.size); w2.u16(answers.size); w2.u16(0); w2.u16(0)
            for (q in questions) {
                w2.name(q.name); w2.u16(q.type); w2.u16(1)
            }
            for (r in answers) w2.record(r, ttlOverride)
            if (w2.size > MAX_PACKET) return null
            return w2.bytes()
        }
        return w.bytes()
    }

    /** 手动增长的可回填字节缓冲：DNS 记录的 rdlength 要先占位、写完 rdata 再回头填。 */
    private class DnsWriter(initial: Int = 512) {
        private var data = ByteArray(initial)
        private val offsets = HashMap<String, Int>()
        var size = 0
            private set

        private fun ensure(n: Int) {
            if (size + n <= data.size) return
            var cap = data.size
            while (cap < size + n) cap *= 2
            data = data.copyOf(cap)
        }

        fun u8(v: Int) {
            ensure(1); data[size++] = (v and 0xFF).toByte()
        }

        fun u16(v: Int) {
            ensure(2)
            data[size++] = ((v shr 8) and 0xFF).toByte()
            data[size++] = (v and 0xFF).toByte()
        }

        fun u32(v: Long) {
            u16(((v shr 16) and 0xFFFF).toInt())
            u16((v and 0xFFFF).toInt())
        }

        fun bytes(b: ByteArray) {
            ensure(b.size); System.arraycopy(b, 0, data, size, b.size); size += b.size
        }

        fun patchU16(at: Int, v: Int) {
            data[at] = ((v shr 8) and 0xFF).toByte()
            data[at + 1] = (v and 0xFF).toByte()
        }

        /** 写域名，带压缩指针：已出现过的后缀直接指回原偏移。 */
        fun name(labels: List<String>) {
            for (i in labels.indices) {
                val key = labels.subList(i, labels.size).joinToString(".").lowercase()
                val seen = offsets[key]
                if (seen != null) {
                    u16(0xC000 or seen)
                    return
                }
                offsets[key] = size
                val raw = labels[i].toByteArray(Charsets.UTF_8)
                u8(raw.size)                      // 我们的标签都远短于 63
                bytes(raw)
            }
            u8(0)
        }

        /** TXT rdata：若干个 `长度(1)+内容` 串。单个串上限 255 字节。 */
        fun txt(pairs: List<String>) {
            for (p in pairs) {
                var raw = p.toByteArray(Charsets.UTF_8)
                if (raw.size > 255) raw = raw.copyOf(255)
                u8(raw.size)
                bytes(raw)
            }
        }

        fun record(r: Rec, ttlOverride: Int?) {
            name(r.name)
            u16(r.type)
            u16(if (r.unique) 1 or CACHE_FLUSH else 1)
            u32((ttlOverride ?: r.ttl).toLong() and 0xFFFFFFFFL)
            val lenAt = size
            u16(0)
            val before = size
            r.rdata(this)
            patchU16(lenAt, size - before)
        }

        fun bytes(): ByteArray = data.copyOf(size)
    }

    // ── DNS 解码 ────────────────────────────────────────────────────────────

    private class Question(
        val name: List<String>,
        val type: Int,
        /** QU 位：客户端要求单播应答。 */
        val uni: Boolean,
    )

    private class DnsReader(private val b: ByteArray, private val len: Int) {
        private var p = 0

        fun u8(): Int? { if (p + 1 > len) return null; return b[p++].toInt() and 0xFF }
        fun u16(): Int? {
            if (p + 2 > len) return null
            val v = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
            p += 2
            return v
        }

        fun question(): Question? {
            val name = name() ?: return null
            val type = u16() ?: return null
            val cls = u16() ?: return null
            return Question(name, type, cls and 0x8000 != 0)
        }

        /** 域名解析，支持压缩指针（指针只能往回指，但这里只做跳数上限保护）。 */
        fun name(): List<String>? {
            val out = ArrayList<String>(4)
            var pos = p
            var jumped = false
            var guard = 0
            while (true) {
                if (pos >= len) return null
                val l = b[pos].toInt() and 0xFF
                if (l == 0) {
                    pos++
                    break
                }
                if (l and 0xC0 == 0xC0) {
                    if (pos + 1 >= len) return null
                    val ptr = ((l and 0x3F) shl 8) or (b[pos + 1].toInt() and 0xFF)
                    if (!jumped) { p = pos + 2; jumped = true }
                    pos = ptr
                    if (++guard > 64) return null
                    continue
                }
                pos++
                if (pos + l > len) return null
                out += String(b, pos, l, Charsets.UTF_8)
                pos += l
                if (out.size > 32) return null
            }
            if (!jumped) p = pos
            return out
        }
    }
}
