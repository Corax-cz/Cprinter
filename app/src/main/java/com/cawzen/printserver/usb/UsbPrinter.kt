package com.cawzen.printserver.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.cawzen.printserver.AppSettings
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * USB 层（L1）。只做三件事：找到打印机、拿到 Bulk OUT 端点、可靠地把字节写完。
 *
 * 关于分块：单次 bulkTransfer 的 length 一律 <= 16384。API 28 之前内核会静默截断，
 * 之后虽不截断，但大块传输在部分 xHCI 控制器上仍会返回 -1 或部分写，所以这里
 * 既限块大小，也把"部分写"当正常情况处理（offset += sent 而非 += len）。
 */
class UsbPrinter private constructor(
    private val appContext: Context,
    val device: UsbDevice,
    conn0: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    val outEndpoint: UsbEndpoint,
) {

    /**
     * 当前 usbfs 句柄。用 var 是为了能在端点出问题时**关掉重开**：
     * 主机侧 pipe 的 halted 标志只有在句柄释放时才会被内核复位，
     * [UsbDeviceConnection] 没有暴露 libusb 的 `clearHalt()`。
     */
    @Volatile
    private var connection: UsbDeviceConnection = conn0

    companion object {
        // 保留这两个常量仅为向后兼容（旧代码引用），新逻辑走 PrinterModel 的厂商级识别。
        const val VENDOR_ID = 0x17ef      // Lenovo
        const val PRODUCT_ID = 0x5411     // LJ2600D (Brother HL-2240D core)

        const val CHUNK_SIZE = 16384
        const val TRANSFER_TIMEOUT_MS = 5000
        const val MAX_RETRIES = 3
        const val RETRY_BACKOFF_MS = 500L
        private const val TAG = "WPrint/Usb"

        private const val ACTION_USB_PERMISSION = "com.cawzen.printserver.USB_PERMISSION"

        fun manager(context: Context): UsbManager =
            context.getSystemService(Context.USB_SERVICE) as UsbManager

        /** 按 VID/PID 匹配，绝不硬编码设备节点或总线号。 */
        fun findDevice(context: Context): UsbDevice? =
            manager(context).deviceList.values.firstOrNull {
                it.vendorId == VENDOR_ID && it.productId == PRODUCT_ID
            }

        /** 用户在设置里手动指定的机型 id；空串 = 自动识别。 */
        fun overrideModelId(context: Context): String? =
            AppSettings.load(context).printerModelId.takeIf { it.isNotBlank() }

        /**
         * 找到第一台「可支持」的打印机。
         *
         * 识别顺序：手动指定 → 精确 VID/PID 表 → 厂商级（Brother/Lenovo 打印机类）。
         * 手动指定能让 VID/PID 表覆盖不到的 OEM 机器也接进来。
         */
        fun findSupportedPrinter(context: Context): UsbDevice? {
            val ov = overrideModelId(context)
            return manager(context).deviceList.values.firstOrNull {
                PrinterModel.identify(it, ov) != null
            }
        }

        /** 识别某台设备的型号信息，供界面显示「接了什么型号」。 */
        fun modelOf(context: Context, device: UsbDevice): PrinterModel.Info? =
            PrinterModel.identify(device, overrideModelId(context))

        /**
         * 当前走哪条驱动路径。
         *
         * 只有手动选了「通用直通」才会变成 PASSTHROUGH —— 自动识别永远只认兄弟系
         * （brlaser 是 Brother 机芯编码器，对 HP/佳能不成立）。这个判断决定作业
         * 是「渲染成兄弟 PCL」还是「原样转发」，两边不能搞混：把 PDF 直通给一台
         * 不支持 PDF 的机器，纸面上什么都不出，看起来像驱动坏了。
         */
        fun driverOf(context: Context): PrinterModel.Driver =
            PrinterModel.byId(overrideModelId(context))?.driver ?: PrinterModel.Driver.BRLASER

        /** 当前枚举到的全部设备，供诊断区分「一个都没有」和「有但不是目标」。 */
        fun allDevices(context: Context): List<UsbDevice> =
            manager(context).deviceList.values.toList()

        /** 枚举所有 USB 设备，用于诊断（Stage 0 失败时要知道到底看到了什么）。 */
        fun dumpAllDevices(context: Context): String {
            val devices = manager(context).deviceList.values
            if (devices.isEmpty()) return "未发现任何 USB 设备"
            return devices.joinToString("\n") { d ->
                "VID=0x%04X PID=0x%04X %s".format(d.vendorId, d.productId, d.deviceName)
            }
        }

        /** 接口与端点全集，Stage 0 的验收依据。 */
        fun describe(device: UsbDevice): String {
            val sb = StringBuilder()
            sb.append("设备 %s  VID=0x%04X PID=0x%04X  接口数=%d\n"
                .format(device.deviceName, device.vendorId, device.productId, device.interfaceCount))
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                sb.append("  接口[$i] id=%d class=0x%02X subclass=0x%02X 端点数=%d\n"
                    .format(iface.id, iface.interfaceClass, iface.interfaceSubclass, iface.endpointCount))
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    val dir = if (ep.direction == UsbConstants.USB_DIR_OUT) "OUT" else "IN"
                    val type = when (ep.type) {
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
                        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
                        else -> "CTRL"
                    }
                    sb.append("    端点 addr=0x%02X %s %s maxPacket=%d interval=%d\n"
                        .format(ep.address, dir, type, ep.maxPacketSize, ep.interval))
                }
            }
            return sb.toString()
        }

        /** 找 Bulk OUT 端点所在的接口。打印机通常只有一个 interface，但不去假设。 */
        fun findBulkOut(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.direction == UsbConstants.USB_DIR_OUT
                    ) {
                        return iface to ep
                    }
                }
            }
            return null
        }

        /**
         * 走标准 PendingIntent 流程申请权限。
         * FLAG_MUTABLE 是必须的：系统要把 EXTRA_DEVICE 回填进这个 Intent，
         * 用 FLAG_IMMUTABLE 在 Android 12+ 上会导致权限回调里拿不到设备。
         */
        suspend fun requestPermission(context: Context, device: UsbDevice): Boolean {
            val mgr = manager(context)
            if (mgr.hasPermission(device)) return true

            return suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        if (intent?.action != ACTION_USB_PERMISSION) return
                        runCatching { context.unregisterReceiver(this) }
                        val granted =
                            intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (cont.isActive) cont.resume(granted)
                    }
                }
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(ACTION_USB_PERMISSION),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                val pi = PendingIntent.getBroadcast(context, 0, intent, flags)
                mgr.requestPermission(device, pi)

                cont.invokeOnCancellation {
                    runCatching { context.unregisterReceiver(receiver) }
                }
            }
        }

        /** 打开设备并 claim 接口。失败返回 null，不抛异常（Stage 0 要报告而不是崩）。 */
        fun open(context: Context, device: UsbDevice): UsbPrinter? {
            val mgr = manager(context)
            if (!mgr.hasPermission(device)) return null
            val found = findBulkOut(device) ?: return null
            val (iface, ep) = found

            val conn = mgr.openDevice(device) ?: return null
            if (!conn.claimInterface(iface, true)) {
                conn.close()
                return null
            }
            return UsbPrinter(context.applicationContext, device, conn, iface, ep)
        }
    }

    /**
     * 清除 Bulk OUT 端点的 HALT 状态（USB 规范 CLEAR_FEATURE(ENDPOINT_HALT)）。
     *
     * Android 的 [UsbDeviceConnection] **没有** `clearHalt()` —— 那是 libusb 的 API。
     * 只能按 USB 2.0 §9.4.1 自己发一个标准控制请求：
     *
     * | 字段 | 值 | 含义 |
     * |---|---|---|
     * | bmRequestType | 0x02 | Host→Device / Standard / **Endpoint** 收件人 |
     * | bRequest | 0x01 | CLEAR_FEATURE |
     * | wValue | 0x0000 | ENDPOINT_HALT |
     * | wIndex | 端点地址 | 含方向位，直接用 [UsbEndpoint.getAddress] |
     * | wLength | 0 | 无数据段 |
     *
     * 返回 true 表示控制器接受了请求。
     */
    private fun clearHalt(): Boolean = runCatching {
        connection.controlTransfer(
            0x02, 0x01, 0x0000, outEndpoint.address, null, 0, TRANSFER_TIMEOUT_MS,
        ) >= 0
    }.getOrDefault(false)

    /**
     * 给打印机发 PJL 通用退出序列（UEL），丢弃它正在接收的作业。
     *
     * `ESC%-12345X` 是 PJL 规范里的「退出当前语言、回到 PJL 空闲态」。在
     * `@PJL ENTER LANGUAGE = PCL` 之后收到它，打印机必须放弃当前页、清空行缓冲。
     * 这是**唯一**能不拔电就让打印机从半截光栅流里脱身的手段 —— 打印机的 PCL
     * 解析器没有超时自救，它会一直等 `1030M` 和分页符。
     */
    fun sendUel(): Boolean = runCatching {
        // 端点若是 HALT，任何 bulkTransfer 都会立刻 -1，先清掉再发。
        clearHalt()
        val uel = "\u001B%-12345X\n".toByteArray(Charsets.US_ASCII)
        connection.bulkTransfer(outEndpoint, uel, uel.size, 2000) == uel.size
    }.getOrDefault(false)

    /**
     * 只生成字节流、不真的发给打印机。
     *
     * 用途只有一个：把「渲染/编码」和「USB 传输」这两段分开验证。排查白纸时，
     * 最要紧的是先确定发出去的东西本身对不对 —— 每验证一次就打一张废纸，
     * 既浪费纸也没法反复做。
     */
    @Volatile
    var dryRun: Boolean = false

    /**
     * USB Printer Class 1.0 §3.2 的 SOFT_RESET。
     *
     * | 字段 | 值 |
     * |---|---|
     * | bmRequestType | 0x21 | Host→Device / **Class** / Interface 收件人 |
     * | bRequest | 0x02 | SOFT_RESET |
     * | wValue / wIndex | 0 / 接口号 |
     * | wLength | 0 |
     *
     * 收到它，打印机必须复位自己的 USB 接口并**丢弃接收缓冲里的残留数据**。
     * 这是「打印机能枚举、EP0 还能读状态、但 bulk OUT 一直被 NAK」时唯一
     * 不碰硬件的解法 —— 那种状态是上一次作业写到一半中断（缺纸、取消、
     * 进程被杀）留下的，打印机在等永远不来的剩余光栅行，缓冲满了就拒收。
     *
     * 注意它和 UEL 不是一回事：UEL 是 PJL 语言层的退出序列，走的是同一条
     * 数据管道，缓冲满了照样发不进去；SOFT_RESET 走控制端点，能插队。
     */
    fun softReset(): Boolean = runCatching {
        connection.controlTransfer(
            0x21, 0x02, 0x0000, usbInterface.id, null, 0, TRANSFER_TIMEOUT_MS,
        ) >= 0
    }.getOrDefault(false)

    /**
     * 释放 usbfs 句柄后重新打开并 claim 接口。
     *
     * 能治的：主机侧端点停在 HALT（`CLEAR_FEATURE` 控制请求清不掉的那个
     * `ep->halted`，只有释放文件描述符才复位）。这种情况 bulkTransfer 会**立刻**
     * 返回 -1。
     *
     * 治不了的：打印机自己在 NAK。那种 bulkTransfer 会**卡满超时**（这里是 5 秒）
     * 才返回 -1，说明设备在拒收 —— 字节一个都没进去，主机侧怎么重开都没用，
     * 得让打印机自己把缓冲吐出来（按 Go 键 / 断电重启）。
     *
     * 两者在日志里的区别就是「立刻」和「5 秒」，不要混为一谈。
     */
    fun reopen(): Boolean {
        val mgr = manager(appContext)
        if (!mgr.hasPermission(device)) return false
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
        // 内核释放接口是异步的，立刻重开偶尔会拿到「接口仍被占用」。
        Thread.sleep(150)
        val fresh = runCatching { mgr.openDevice(device) }.getOrNull() ?: return false
        if (!fresh.claimInterface(usbInterface, true)) {
            runCatching { fresh.close() }
            return false
        }
        connection = fresh
        Log.i(TAG, "已关闭并重新打开 USB 连接（device=${device.deviceName}）")
        return true
    }

    /**
     * 分块写完整段字节流。
     *
     * - 单块 <= [CHUNK_SIZE]
     * - 返回 -1 时退避重试，最多 [MAX_RETRIES] 次；仍失败则抛 [IOException]，绝不静默跳过
     * - 返回 0 < sent < len 视为部分写，正常推进 offset
     *
     * @return 实际写入字节数（等于 data.size 才算成功）
     */
    @Throws(IOException::class)
    fun writeAll(data: ByteArray, onProgress: ((written: Int, total: Int) -> Unit)? = null): Int {
        if (dryRun) {
            Log.w(TAG, "dryRun=true，跳过 USB 写入（$data.size 字节未发送）")
            return data.size
        }
        // 写入前先读一次设备状态。写不进去时，「缺纸 / 离线」这类硬件原因和
        // 「端点卡死」在日志里必须能分开看 —— 否则只剩一个裸 -1，只能靠猜。
        val statusBefore = portStatusText()
        Log.i(TAG, "写入前状态：$statusBefore；待写 ${data.size} 字节")
        var offset = 0
        var retries = 0
        var recovered = false
        // 本轮里「卡满超时」的次数。用来区分「打印机拒收」和「主机端点停住」。
        var slowFails = 0
        while (offset < data.size) {
            val len = minOf(CHUNK_SIZE, data.size - offset)
            val started = System.currentTimeMillis()
            val sent = connection.bulkTransfer(outEndpoint, data, offset, len, TRANSFER_TIMEOUT_MS)
            val spent = System.currentTimeMillis() - started

            if (sent > 0) {
                offset += sent
                retries = 0
                slowFails = 0
                onProgress?.invoke(offset, data.size)
                continue
            }
            if (spent >= TRANSFER_TIMEOUT_MS - 500) slowFails++

            // -1 有两种完全不同的成因，看耗时就能分开：
            //
            //   · **立刻返回**（几毫秒）：主机侧端点停在 HALT。上一次写入被中断
            //     （进程被 force-stop、作业取消、设备复位）就会这样，之后**每一次**
            //     bulkTransfer 都秒失败，退避多久都没用。
            //   · **卡满超时**（这里 5 秒才返回）：打印机在 NAK，一个字节都没收。
            //     典型成因是它的接收缓冲里还压着上一个作业的残页 —— 面板可能显示
            //     就绪，但它就是不收新数据。
            //
            // 前者能靠 CLEAR_FEATURE 或关句柄重开治好，后者只能让打印机自己
            // 把缓冲吐出来。两者混在一起报「USB 写入失败」，就会把人往换线、
            // 重装驱动的方向带偏。
            val cleared = clearHalt()
            if (cleared) {
                Log.i(TAG, "端点疑似 HALT，已发 CLEAR_FEATURE(ENDPOINT_HALT)，立即重试")
            }

            retries++
            if (retries >= MAX_RETRIES) {
                // 还没发出去任何字节，说明问题不在「流断了半截」，可以放心复位。
                // 顺序有讲究：先发 SOFT_RESET（走控制端点，能插进满缓冲），
                // 治不了再关句柄重开（复位主机侧的 halted 标志）。
                if (offset == 0 && !recovered) {
                    recovered = true
                    val sr = softReset()
                    Log.w(TAG, "从第一个字节就失败，已发 USB SOFT_RESET=$sr")
                    Thread.sleep(600)
                    if (reopen()) {
                        Log.w(TAG, "已重开 USB 连接，再试一次")
                        retries = 0
                        continue
                    }
                }
                // 放弃之前必须把打印机从「PCL 光栅流接收到一半」的状态里拽出来。
                //
                // Brother 机芯在收到 `ESC*b1030m` 之后就进入等光栅行的状态，
                // 只有 `1030M` + FF 才算这一页结束。半截流留在缓冲里，打印机
                // 会一直认为「还有数据要打」：面板停在处理中、反复走空白纸，
                // 直到纸盒见底 —— 纸面上的表现是「一直在吐白纸」，而不是报错。
                // UEL（`ESC%-12345X`）是 PJL 的通用退出序列，任何状态下收到它
                // 都会丢弃当前作业、回到空闲。代价只是这一个作业作废，而它本来
                // 就失败了；不做这一步的代价是打印机卡死到断电为止。
                val uelSent = sendUel()
                Log.w(TAG, "已向打印机发 UEL 复位：$uelSent（清掉半截 PCL 流）")
                // 用「本轮里出现过慢失败」而不是「最后一次的耗时」判定：
                // 最后一次偶尔会提前返回（前面已经耗掉时间），只看它会把
                // 「打印机拒收」误判成「主机端点停住」，把排查方向带偏。
                val stubborn = slowFails > 0
                throw IOException(
                    buildString {
                        append("USB 写入失败：offset=$offset/${data.size}，")
                        append("连续 $retries 次返回 $sent（耗时 ${spent}ms，慢失败 $slowFails 次）")
                        if (stubborn) {
                            append("。打印机在拒收数据：bulk OUT 一直 NAK，每次都要等满超时才失败。")
                            append("最常见的原因是**纸盒没推到位、或前盖/顶盖没关好** —— ")
                            append("这种状态下打印机会照常回「有纸」，但一个字节都不收。")
                            append("先检查纸盒和盖板；仍然不行再关掉打印机电源重启。")
                        } else {
                            append("。主机侧端点已停住（秒失败），")
                            append("CLEAR_FEATURE / SOFT_RESET / 重开连接都没救回来，需要拔插一次 USB 线。")
                        }
                        append("（清除 HALT=${if (cleared) "成功" else "未生效"}；UEL=$uelSent；")
                        append("打印机状态 $statusBefore）")
                    }
                )
            }
            Thread.sleep(RETRY_BACKOFF_MS * retries)
        }
        return offset
    }

    /**
     * GET_PORT_STATUS（class 请求 0x01，recipient=Interface），返回**一个字节**。
     *
     * 位定义见 USB Printer Class 1.0 §6.3.2，就下面这一套，没有第二种解释：
     *
     * | 位 | 掩码 | 含义 | 有效电平 |
     * |---|---|---|---|
     * | 3 | 0x08 | nFault | **低**有效（0=故障，1=正常） |
     * | 4 | 0x10 | Select（在线） | 高有效（1=在线） |
     * | 5 | 0x20 | PaperEmpty | **高**有效（1=缺纸） |
     * | 6/7 | 0x40/0x80 | 保留 | — |
     *
     * 实测对账（HL-2240D 机芯，有纸 / 抽出纸盒两种状态）：
     *
     * - 就绪 → `0x18` = nFault 1 + Select 1 + PaperEmpty 0  → 无故障、在线、有纸
     * - 缺纸 → `0x20` = PaperEmpty 1                       → 缺纸
     *
     * 两个读数与标准**完全一致**。曾经以为这台机芯「不符合标准」，把 bit3 当成了
     * 缺纸位 —— 那正好把就绪（0x18）读成缺纸。后果不是显示错一行字：`runJob()`
     * 在提交前会 `isPaperEmpty()` 判断，于是**每一个作业都被判定缺纸、一个字节
     * 都不发就 ABORTED**，客户端看到的就是「点了打印没反应」。
     *
     * 以前这里还按 2 字节读并用 `read < 2` 判失败，而设备只回 1 字节，于是
     * 每次都被判成「状态不可读(-1)」。读多少字节、每一位什么意思，都必须与
     * 规范逐个对上，不能靠单次现象反推。
     */
    fun portStatus(): Int {
        val buffer = ByteArray(1)
        val read = connection.controlTransfer(
            0xA1, 0x01, 0x0000, usbInterface.id, buffer, 1, 1000,
        )
        if (read < 1) return -1
        return buffer[0].toInt() and 0xFF
    }

    /** 缺纸 = bit5（PaperEmpty，高有效）。写作业前据此拒绝，否则只会白走纸。 */
    fun isPaperEmpty(): Boolean = portStatus().let { it >= 0 && (it and 0x20) != 0 }

    /**
     * 解出的三态。**全工程只有这里做位运算**。
     *
     * 之前 `UsbPrinter`、`PrinterProbe`、`IppServer` 三处各写一遍位判断，其中两处
     * 把 bit3 当缺纸，改一处不改另一处就会互相打架。集中到一处，界面、诊断页、
     * 作业前置检查都从这里取值。
     */
    data class PortState(val raw: Int, val paperEmpty: Boolean, val offline: Boolean, val fault: Boolean) {
        val ok: Boolean get() = !paperEmpty && !offline && !fault

        fun text(): String {
            val faults = buildList {
                if (paperEmpty) add("缺纸")
                if (offline) add("离线")
                if (fault) add("故障")
            }
            val head = if (faults.isEmpty()) "就绪" else faults.joinToString("、")
            return "0x%02X %s".format(raw, head)
        }
    }

    /** 读一次并解码；读不到返回 null（调用方必须区分「读不到」和「正常」）。 */
    fun portState(): PortState? {
        val s = portStatus()
        if (s < 0) return null
        return PortState(s, s and 0x20 != 0, s and 0x10 == 0, s and 0x08 == 0)
    }

    /**
     * 可读状态描述。只翻译这三位，其余保留原始十六进制。
     *
     * 三个位都有明确电平定义，所以这里可以直接给结论；读不到（-1）时说读不到，
     * 不猜。
     */
    fun portStatusText(): String = portState()?.text() ?: "状态不可读（-1）"

    fun close() {
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
    }
}
