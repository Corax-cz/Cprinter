package com.cawzen.printserver.diag

import android.content.Context
import com.cawzen.printserver.PrintOptions
import com.cawzen.printserver.PrintService
import com.cawzen.printserver.Stage0
import com.cawzen.printserver.net.NetInfo
import com.cawzen.printserver.print.NativeBr
import com.cawzen.printserver.print.PdfPrint
import com.cawzen.printserver.print.TestPdf
import com.cawzen.printserver.usb.PrinterModel
import com.cawzen.printserver.usb.PrinterProbe
import com.cawzen.printserver.usb.UsbGate
import com.cawzen.printserver.usb.UsbPrinter
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 诊断。每一项都是**真的去问一次**，不是读开关猜状态。
 *
 * 设计原则：宁可少一项，也不要给一个「看起来正常」的推测项。用户点诊断，
 * 是为了区分三种情况 —— 没插好、软件没起、打印机不认数据；一个不落地的
 * 「已连接 ✓」会让排查从第一步就走错方向。
 */
object Diagnostics {

    enum class Level { OK, WARN, FAIL, INFO }

    data class Item(
        val level: Level,
        val title: String,
        val detail: String,
    ) {
        fun line(): String = when (level) {
            Level.OK -> "[ OK ] $title — $detail"
            Level.WARN -> "[WARN] $title — $detail"
            Level.FAIL -> "[FAIL] $title — $detail"
            Level.INFO -> "[INFO] $title — $detail"
        }
    }

    data class Report(val items: List<Item>, val elapsedMs: Long, val at: Long) {
        fun toText(): String = buildString {
            appendLine("Cprinter 诊断报告 ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(at))}")
            appendLine("耗时 ${elapsedMs}ms")
            appendLine("-".repeat(40))
            items.forEach { appendLine(it.line()) }
        }

        val worst: Level
            get() = when {
                items.any { it.level == Level.FAIL } -> Level.FAIL
                items.any { it.level == Level.WARN } -> Level.WARN
                else -> Level.OK
            }
    }

    /**
     * @param includeRender 是否跑一次「渲染 + 编码 + 丢弃」的自检。
     *   会真的打开 USB 设备、真的渲染一页 A4 —— 只是不发送，所以不耗纸。
     *   耗时 3~10 秒，默认不跑。
     */
    suspend fun run(context: Context, includeRender: Boolean = false): Report =
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val items = ArrayList<Item>()

            // 1. USB 枚举与端点
            val device = UsbPrinter.findSupportedPrinter(context)
            if (device == null) {
                val all = UsbPrinter.allDevices(context)
                items += Item(
                    Level.FAIL, "USB 打印机",
                    if (all.isEmpty()) "没有枚举到任何 USB 设备（检查 OTG 线与打印机电源）"
                    else "枚举到 ${all.size} 个 USB 设备，但没有受支持的打印机：\n" +
                        PrinterModel.describeDevices(all, UsbPrinter.overrideModelId(context)),
                )
            } else {
                val model = UsbPrinter.modelOf(context, device)
                val out = UsbPrinter.findBulkOut(device)
                items += Item(
                    if (out != null) Level.OK else Level.FAIL,
                    "USB 打印机",
                    "VID=0x%04X PID=0x%04X %s；%s".format(
                        device.vendorId, device.productId,
                        model?.displayName ?: "未知型号",
                        if (out != null) "Bulk OUT 端点 0x%02X".format(out.second.address)
                        else "找不到 Bulk OUT 端点，无法打印",
                    ),
                )
                // 2. USB 权限
                val granted = UsbPrinter.manager(context).hasPermission(device)
                items += Item(
                    if (granted) Level.OK else Level.FAIL,
                    "USB 访问权限",
                    if (granted) "已授权" else "未授权：请回首页点「授权 USB 访问」",
                )
            }

            // 3. 端口状态（这一项要开设备，只在没打印时读）
            if (device != null) {
                val snap = PrinterProbe.read(context)
                items += Item(
                    when {
                        snap.ok && snap.paperEmpty -> Level.WARN
                        snap.ok -> Level.OK
                        UsbGate.isBusy -> Level.INFO
                        else -> Level.WARN
                    },
                    "打印机状态位",
                    if (snap.ok) "${snap.text}（0x%02X）".format(snap.raw) else snap.note,
                )
            }

            // 4. IPP 服务
            val st = PrintService.instance?.statusSnapshot()
            if (st == null) {
                items += Item(Level.FAIL, "IPP 服务", "共享服务没有运行（回首页启动共享）")
            } else {
                items += Item(
                    if (st.running) Level.OK else Level.FAIL,
                    "IPP 服务",
                    if (st.running) "监听 ${st.port} · ${st.ippUri}" else "端口绑定失败",
                )
                items += Item(
                    when {
                        st.airprint -> Level.OK
                        st.mdnsName.isNotBlank() -> Level.WARN
                        else -> Level.FAIL
                    },
                    "AirPrint 广播",
                    when {
                        st.airprint -> "已广播 _universal 子类型（iPhone / iPad 可见）"
                        st.mdnsName.isNotBlank() ->
                            "只广播了 _ipp._tcp，iOS 不会出现在列表里：${st.mdnsError}"
                        else -> "mDNS 未注册：${st.mdnsError.ifBlank { "未知原因" }}"
                    },
                )
                items += Item(
                    if (st.rawRunning) Level.OK else Level.INFO,
                    "RAW 端口",
                    if (st.rawRunning) "监听 ${st.rawPort}（Windows 标准 TCP/IP 端口）"
                    else "未开启",
                )
            }

            // 5. 网络
            val wifi = NetInfo.wifi(context)
            items += Item(
                if (wifi != null) Level.OK else Level.FAIL,
                "局域网",
                if (wifi != null) "接口 ${wifi.name} · ${wifi.ip}"
                else "没有可用的 Wi-Fi / 以太网接口（打印机和客户端必须在同一网络）",
            )

            // 6. 编码内核（JNI）。真的跑一次小图，不碰 USB。
            items += try {
                val px = ByteArray(64 * 64) { 0x80.toByte() }
                val out = NativeBr.nativeRender(px, 64, 64, 64, 300, false, "A4")
                if (out != null && out.isNotEmpty()) {
                    Item(Level.OK, "渲染内核", "brlaser JNI 正常，小图编码 ${out.size} 字节")
                } else {
                    Item(Level.FAIL, "渲染内核", "nativeRender 返回空数据")
                }
            } catch (t: Throwable) {
                Item(Level.FAIL, "渲染内核", "JNI 加载/调用失败：${t.javaClass.simpleName}: ${t.message}")
            }

            // 7. 队列
            val store = PrintService.instance?.jobsOrNull()
            if (store != null) {
                val live = store.snapshot()
                val failed = store.history().count { it.state == com.cawzen.printserver.ipp.Ipp.JOB_STATE_ABORTED }
                items += Item(
                    if (failed > 0) Level.WARN else Level.INFO,
                    "队列",
                    "内存中 ${live.size} 条（等待 ${store.queuedCount()}），历史失败 $failed 条" +
                        if (store.paused) "；队列已暂停" else "",
                )
            }

            // 8. 端到端渲染自检（不发送）
            if (includeRender && device != null) {
                items += renderSelfTest(context)
            }

            Report(items, System.currentTimeMillis() - t0, System.currentTimeMillis())
        }

    /**
     * 用真实 PDF 走完整渲染管线，但把 USB 写入关掉（`dryRun`）。
     *
     * 这一段能抓到的错：PDF 解析、条带渲染、brlaser 编码、PCL 头生成。
     * 抓不到的错：USB 传输本身、打印机机械部分。两者分开才有意义 ——
     * 白纸问题里最常见的混淆就是「不知道是数据错了还是没送到」。
     */
    private suspend fun renderSelfTest(context: Context): Item {
        val locked = runCatching { UsbGate.lock.tryLock(2, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!locked) return Item(Level.INFO, "渲染自检", "正在打印，跳过")
        var p: UsbPrinter? = null
        return try {
            val pdf = TestPdf.generate(
                File(context.cacheDir, "diag.pdf"), pages = 1,
                options = PrintOptions(dpi = 300, duplex = false),
                context = context,
            )
            val opened = Stage0.run(context, Stage0.Mode.EXCLUSIVE)
            val printer = opened.printer ?: return Item(Level.FAIL, "渲染自检", "无法打开打印机")
            p = printer
            printer.dryRun = true
            val out = PdfPrint.print(printer, pdf, PrintOptions(dpi = 300, duplex = false))
            Item(
                if (out.ok) Level.OK else Level.FAIL,
                "渲染自检（不发送）",
                if (out.ok) "1 页 A4@300dpi 编码完成（未写 USB，不耗纸）"
                else "编码失败：${out.report.take(200)}",
            )
        } catch (t: Throwable) {
            Item(Level.FAIL, "渲染自检（不发送）", "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { p?.close() }
            runCatching { UsbGate.lock.unlock() }
        }
    }
}
