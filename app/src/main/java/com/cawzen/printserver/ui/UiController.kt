package com.cawzen.printserver.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.cawzen.printserver.AppSettings
import com.cawzen.printserver.PrintOptions
import com.cawzen.printserver.PrintService
import com.cawzen.printserver.Stage0
import com.cawzen.printserver.diag.Diagnostics
import com.cawzen.printserver.ipp.ClientRegistry
import com.cawzen.printserver.ipp.Ipp
import com.cawzen.printserver.ipp.JobStore
import com.cawzen.printserver.print.DocumentSniffer
import com.cawzen.printserver.print.PdfPrint
import com.cawzen.printserver.print.TestPdf
import com.cawzen.printserver.usb.PrinterModel
import com.cawzen.printserver.usb.PrinterProbe
import com.cawzen.printserver.usb.UsbPrinter
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "WPrint/UI"

// ── 界面数据模型 ────────────────────────────────────────────────────────────
//
// 全部是不可变 data class，并且**从可变对象拷出来**。理由很实际：JobStore 里的
// Job 字段是 @Volatile 可变的，Compose 对「同一个实例内容变了」完全无感 ——
// 直接把 Job 丢进 UI，队列会一直显示第一次看到的状态。

data class DeviceState(
    val connected: Boolean = false,
    val name: String = "",
    val exact: Boolean = false,
    /** 型号是用户手动指定的，不是自动识别出来的。 */
    val manual: Boolean = false,
    val vid: Int = 0,
    val pid: Int = 0,
    val node: String = "",
    val permission: Boolean = false,
    /** 系统里枚举到了非目标打印机之外的设备数，用于「插了但不是它」的提示。 */
    val otherDevices: Int = 0,
)

data class JobRow(
    val id: Int,
    val name: String,
    val user: String,
    val client: String,
    val kind: String,
    val state: Int,
    val reasons: String,
    val pages: Int,
    val copies: Int,
    val bytes: Long,
    val createdAt: Long,
    val finishedAt: Long,
    val message: String,
    val filePath: String,
) {
    val running: Boolean get() = state == Ipp.JOB_STATE_PROCESSING
    val pending: Boolean get() = state == Ipp.JOB_STATE_PENDING
    val completed: Boolean get() = state == Ipp.JOB_STATE_COMPLETED
    val failed: Boolean get() = state == Ipp.JOB_STATE_ABORTED || state == Ipp.JOB_STATE_CANCELED

    val stateLabel: String
        get() = when (state) {
            Ipp.JOB_STATE_PENDING -> "排队中"
            Ipp.JOB_STATE_PROCESSING -> "打印中"
            Ipp.JOB_STATE_COMPLETED -> "已完成"
            Ipp.JOB_STATE_CANCELED -> "已取消"
            Ipp.JOB_STATE_ABORTED -> "失败"
            else -> "未知"
        }

    val tone: Tone
        get() = when {
            running -> Tone.Ok
            pending -> Tone.Warn
            completed -> Tone.Idle
            else -> Tone.Bad
        }

    /** 失败原因的人话解释。 */
    val failureHint: String
        get() {
            if (!failed) return ""
            val m = message
            return when {
                m.contains("PaperEmpty") || m.contains("缺纸") -> "E02 缺纸：装好纸张后点「重试」。"
                m.contains("卡纸") -> "E04 卡纸：打开前盖取出卡纸，合盖后点「重试」。"
                m.contains("无法打开打印机") -> "E01 打印机不可用：检查 USB 线与打印机电源。"
                // 拒收和端点停住是两回事，给的自救动作也不同。这里必须区分开，
                // 否则用户会按「拔线」去做，而拔线对拒收完全无效（打印机自己的
                // 缓冲不会因为 USB 重枚举而清空）—— 只有断电才行。
                m.contains("拒收数据") ->
                    "E03 打印机拒收数据：USB 管道一直被 NAK，一个字节都没进去。" +
                        "先检查纸盒有没有推到位、前盖/顶盖有没有关好 —— 这种状态下打印机" +
                        "仍会显示「有纸」；都不行再把打印机电源关掉重开，然后点「重试」。"
                m.contains("主机侧端点已停住") ->
                    "E03 主机侧 USB 端点卡死：已尝试自动复位无效，请拔插一次 USB 线后重试。"
                m.contains("USB 写入失败") ->
                    "E03 USB 传输中断：打印机可能休眠或没开机。点「复位打印机」后再试。"
                reasons == "job-canceled-by-user" -> "已手动取消。"
                m.isBlank() -> "E09 未知错误：到「维护 → 诊断」跑一次完整诊断。"
                else -> "E09 " + m.lineSequence().first().take(90)
            }
        }
}

data class ClientRow(
    val ip: String,
    val label: String,
    val ua: String,
    val lastSeen: Long,
    val jobs: Int,
    val blocked: Boolean,
    val approved: Boolean,
    /** 最近 30 分钟内活动过。更早的只保留在列表里供授权，不计入「已连接」。 */
    val fresh: Boolean = false,
)

data class Runtime(
    val device: DeviceState = DeviceState(),
    val status: PrintService.Status? = null,
    val jobs: List<JobRow> = emptyList(),
    val history: List<JobRow> = emptyList(),
    val clients: List<ClientRow> = emptyList(),
    val paused: Boolean = false,
) {
    val running: Boolean get() = status?.running == true
    val activeJob: JobRow? get() = jobs.firstOrNull { it.running }
}

private fun JobStore.Job.toRow() = JobRow(
    id = id, name = name, user = user, client = client,
    kind = com.cawzen.printserver.print.DocumentSniffer.describe(kind),
    state = state, reasons = reasons, pages = impressions, copies = copies,
    bytes = runCatching { file.length() }.getOrDefault(0L),
    createdAt = createdAt, finishedAt = finishedAt, message = message,
    filePath = file.absolutePath,
)

private fun JobStore.HistoryEntry.toRow() = JobRow(
    id = id, name = name, user = user, client = client, kind = kind,
    state = state, reasons = reasons, pages = pages, copies = copies, bytes = bytes,
    createdAt = createdAt, finishedAt = finishedAt, message = message, filePath = filePath,
)

private suspend fun buildRuntime(context: Context): Runtime = withContext(Dispatchers.IO) {
    val device = UsbPrinter.findSupportedPrinter(context)
    val model: PrinterModel.Info? = device?.let { UsbPrinter.modelOf(context, it) }
    val dev = DeviceState(
        connected = device != null,
        name = model?.displayName ?: "",
        exact = model?.exact ?: false,
        manual = model?.manual ?: false,
        vid = device?.vendorId ?: 0,
        pid = device?.productId ?: 0,
        node = device?.deviceName ?: "",
        permission = device != null && UsbPrinter.manager(context).hasPermission(device),
        otherDevices = (UsbPrinter.allDevices(context).size - if (device != null) 1 else 0).coerceAtLeast(0),
    )
    val svc = PrintService.instance
    val store = svc?.jobsOrNull()
    val live = store?.snapshot()?.filter {
        it.state == Ipp.JOB_STATE_PENDING || it.state == Ipp.JOB_STATE_PROCESSING
    }?.map { it.toRow() } ?: emptyList()
    val hist = store?.history()?.map { it.toRow() } ?: emptyList()
    val cfg = AppSettings.load(context)
    val clients = ClientRegistry.entries().map {
        ClientRow(
            ip = it.ip,
            label = ClientRegistry.deviceLabel(it.userAgent),
            ua = it.userAgent,
            lastSeen = it.lastSeen,
            jobs = it.jobs,
            blocked = it.blocked,
            approved = it.ip in cfg.approvedClients,
            fresh = System.currentTimeMillis() - it.lastSeen < 30 * 60_000L,
        )
    }
    Runtime(
        device = dev,
        status = svc?.statusSnapshot(),
        jobs = live.sortedBy { it.id },
        history = hist,
        clients = clients,
        paused = store?.paused == true,
    )
}

/** 800ms 轮询。前台服务、USB 热插拔、队列进度都是外部状态，轮询比事件总线可靠。 */
@Composable
fun rememberRuntime(): Runtime {
    val context = LocalContext.current
    val state = produceState(initialValue = Runtime(), context) {
        while (true) {
            value = runCatching { buildRuntime(context) }.getOrElse { Runtime() }
            delay(800)
        }
    }
    return state.value
}

// ── 动作 ────────────────────────────────────────────────────────────────────

/**
 * 把「点一下会发生什么」从界面里抽出来。
 *
 * 界面只负责画和调，不直接碰 USB / 服务 / SharedPreferences。这样按钮的
 * enabled 状态与真实前置条件可以分开写：按钮亮着不等于能成，失败了也必须
 * 有一句人话（[toast]）而不是静默。
 */
class UiController(
    val context: Context,
    private val scope: CoroutineScope,
) {
    var cfg by mutableStateOf(AppSettings.load(context))
        private set

    /** 正在进行的动作名（按钮显示"正在…"并禁用）。 */
    var busy by mutableStateOf<String?>(null)
        private set

    var toast by mutableStateOf<String?>(null)
    var probe by mutableStateOf<PrinterProbe.Snapshot?>(null)
        private set
    var report by mutableStateOf<Diagnostics.Report?>(null)
        private set
    var diagRunning by mutableStateOf(false)
        private set

    fun consumeToast() {
        toast = null
    }

    fun say(msg: String) {
        toast = msg
        Log.i(TAG, msg)
    }

    // ── 设置 ────────────────────────────────────────────────────────────────

    /**
     * 改设置。协议开关要顺带通知服务重配广播。
     *
     * 这里有一个必须替用户处理的依赖：AirPrint 依赖 `_ipp._tcp` 主广播。
     * 用户关掉 Mopria 时如果 AirPrint 还开着，iPhone 会看到一个永远解析不出
     * 地址的服务名 —— 所以联动关掉，并说明原因，而不是留一个空开关。
     */
    fun updateCfg(block: (AppSettings) -> AppSettings) {
        val before = cfg
        val next = block(before)
        cfg = next
        AppSettings.save(context, next)

        val protocolsChanged = before.airprint != next.airprint ||
            before.mopria != next.mopria ||
            before.rawEnabled != next.rawEnabled
        if (protocolsChanged) {
            if (before.mopria && !next.mopria && next.airprint) {
                cfg = next.copy(airprint = false)
                AppSettings.save(context, cfg)
                say("已关闭 AirPrint：它依赖 _ipp._tcp 主广播")
            }
            PrintService.reload(context)
        }
        if (before.allowNewDevices != next.allowNewDevices && next.allowNewDevices) {
            ClientRegistry.entries().forEach { it.blocked = false }
        }
    }

    fun probeNow() {
        if (busy != null) return
        busy = "读取状态"
        scope.launch {
            probe = PrinterProbe.read(context)
            busy = null
        }
    }

    fun runDiagnostics(full: Boolean) {
        if (diagRunning) return
        diagRunning = true
        scope.launch {
            report = Diagnostics.run(context, includeRender = full)
            diagRunning = false
        }
    }

    // ── 共享 ────────────────────────────────────────────────────────────────

    fun startShare() {
        if (busy != null) return
        busy = "启动共享"
        scope.launch {
            val d = UsbPrinter.findSupportedPrinter(context)
            if (d == null) {
                busy = null
                say("没有检测到打印机：请用 USB 线连接打印机并开机")
                return@launch
            }
            if (!UsbPrinter.manager(context).hasPermission(d) &&
                !UsbPrinter.requestPermission(context, d)
            ) {
                busy = null
                say("USB 权限被拒绝，无法启动共享")
                return@launch
            }
            updateCfg { it.copy(shareEnabled = true) }
            PrintService.start(context)
            // 等服务真的起来（onCreate 里的端口绑定是异步的）。
            var ok = false
            for (i in 0 until 20) {
                delay(150)
                if (PrintService.instance?.statusSnapshot()?.running == true) {
                    ok = true
                    break
                }
            }
            busy = null
            say(
                if (ok) "共享已启动，局域网内设备现在可以打印"
                else "共享启动超时：请检查系统是否限制了后台启动"
            )
        }
    }

    fun stopShare() {
        updateCfg { it.copy(shareEnabled = false) }
        PrintService.stop(context)
        say("已暂停共享")
    }

    fun restartShare() {
        busy = "重启共享"
        scope.launch {
            updateCfg { it.copy(shareEnabled = true) }
            PrintService.stop(context)
            delay(700)
            PrintService.start(context)
            delay(900)
            busy = null
            say("共享已重启（端口可能重新分配）")
        }
    }

    // ── 打印 ────────────────────────────────────────────────────────────────

    fun printTestPage(dpi: Int? = null) {
        if (busy != null) return
        busy = "打印测试页"
        scope.launch {
            val msg = try {
                val svc = PrintService.instance
                // 指定 dpi（联调脚本用）时必须直连：队列按提交那一刻的设置渲染。
                if (svc?.statusSnapshot()?.running == true && dpi == null) {
                    withContext(Dispatchers.IO) { svc.submitTestPage() }
                } else {
                    directTestPage(dpi)
                }
            } catch (t: Throwable) {
                "测试页失败：${t.message}"
            }
            busy = null
            say(msg)
        }
    }

    /** 服务没开时直接打。走同一条渲染管线，只是不经过队列。 */
    private suspend fun directTestPage(dpi: Int? = null): String = withContext(Dispatchers.IO) {
        val d = UsbPrinter.findSupportedPrinter(context) ?: return@withContext "没有检测到打印机"
        if (!UsbPrinter.manager(context).hasPermission(d)) return@withContext "没有 USB 权限"
        val saved = PrintOptions.load(context)
        val opts = if (dpi != null) saved.withDpi(dpi) else saved
        val pdf = TestPdf.generate(File(context.cacheDir, "selftest.pdf"), 1, opts, context)
        val opened = Stage0.run(context, Stage0.Mode.EXCLUSIVE)
        val p = opened.printer ?: return@withContext "无法打开打印机：${opened.report.take(160)}"
        try {
            val out = PdfPrint.print(p, pdf, opts)
            if (out.ok) "测试页已发送（${out.pages} 页 @${opts.dpi}dpi）" else "打印失败：${out.report.take(160)}"
        } finally {
            p.close()
        }
    }

    /**
     * 打印手机里的文件（本机直打，不经过局域网）。
     *
     * 走的是和网络作业**完全相同**的队列，所以嗅探分流、PCL 直通、双面、失败归因
     * 全都复用，不必另开一条"只有 PDF 能走"的窄路。也正因为如此，这里不按扩展名
     * 白名单过滤 —— 类型由字节决定（[DocumentSniffer]），`.pdf` 里装 PCL 的情况
     * 在真实文件里很常见，按扩展名筛只会把能打的文件挡在外面。
     *
     * 队列挂在 [PrintService] 上。共享关着时也要能打本机文件，所以服务没跑就先
     * 把它起来 —— 但它起来就意味着端口在监听，因此顺带把 shareEnabled 置真，
     * 免得界面显示"运行中"而设置里却是关的，两边打架。
     */
    fun printLocalFiles(uris: List<Uri>) {
        if (busy != null || uris.isEmpty()) return
        busy = "读取文件"
        scope.launch {
            val added = ArrayList<String>()
            val failed = ArrayList<String>()
            withContext(Dispatchers.IO) {
                if (UsbPrinter.findSupportedPrinter(context) == null) {
                    failed += "没有检测到打印机"
                    return@withContext
                }
                if (PrintService.instance?.statusSnapshot()?.running != true) {
                    updateCfg { it.copy(shareEnabled = true) }
                    PrintService.start(context)
                    for (i in 0 until 20) {
                        delay(150)
                        if (PrintService.instance?.statusSnapshot()?.running == true) break
                    }
                }
                val store = PrintService.instance?.jobsOrNull()
                if (store == null) {
                    failed += "打印服务没能启动"
                    return@withContext
                }
                val opts = PrintOptions.load(context)
                for (u in uris) {
                    val err = enqueueLocal(store, u, opts)
                    if (err == null) added += (displayName(u) ?: "文件") else failed += err
                }
            }
            busy = null
            say(
                when {
                    added.isEmpty() -> failed.joinToString("；").ifBlank { "没有可打印的文件" }
                    failed.isEmpty() -> "已加入队列 ${added.size} 份：${added.joinToString("、")}"
                    else -> "已加入 ${added.size} 份；${failed.joinToString("；")}"
                }
            )
        }
    }

    /**
     * 把一个 SAF 选中的文件搬进作业目录并排队。成功返回 null，失败返回人话。
     *
     * 复制用 64KB 缓冲流式落盘：十页 600dpi 的 PWG 就是 350MB，整包读进内存必崩。
     */
    private fun enqueueLocal(store: JobStore, uri: Uri, opts: PrintOptions): String? {
        val name = displayName(uri) ?: "本机文件"
        val tmp = File(context.cacheDir, "local-${System.nanoTime()}.bin")
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return "$name 打不开"
            input.use { src -> tmp.outputStream().use { dst -> src.copyTo(dst, 64 * 1024) } }
            if (tmp.length() == 0L) {
                tmp.delete()
                return "$name 是空文件"
            }
            // 只用前 1KB 判断类型 —— 嗅探本来就只看头部魔数，没必要为判断类型读整份文档。
            val head = ByteArray(minOf(1024L, tmp.length()).toInt())
            tmp.inputStream().use { it.read(head) }
            val kind = DocumentSniffer.sniff(head)
            if (kind == DocumentSniffer.Kind.UNKNOWN) {
                tmp.delete()
                return "$name 不是可打印格式（只认 PDF / PCL / PostScript / URF / PWG）"
            }
            store.submit(
                name = name,
                user = "本机",
                format = formatOf(kind),
                kind = kind,
                documentFile = tmp,
                // 本机手动打印**听界面设置**：网络作业的双面只信客户端（sides 字段），
                // 这两条规则不同源，别混（见 JobStore.resolveDuplex）。
                duplex = opts.duplex,
                copies = opts.copies,
                client = "",
            )
            null
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            "$name 读取失败：${t.message?.take(60)}"
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun formatOf(kind: DocumentSniffer.Kind): String = when (kind) {
        DocumentSniffer.Kind.PDF -> "application/pdf"
        DocumentSniffer.Kind.POSTSCRIPT -> "application/postscript"
        DocumentSniffer.Kind.PCL -> "application/vnd.hp-pcl"
        DocumentSniffer.Kind.PWG_RASTER -> "image/pwg-raster"
        DocumentSniffer.Kind.URF -> "image/urf"
        DocumentSniffer.Kind.UNKNOWN -> "application/octet-stream"
    }

    fun recoverPrinter() {
        if (busy != null) return
        busy = "复位打印机"
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                PrintService.instance?.recoverPrinter() ?: run {
                    val d = UsbPrinter.findSupportedPrinter(context)
                    if (d == null || !UsbPrinter.manager(context).hasPermission(d)) {
                        "没有可用的打印机"
                    } else {
                        val p = UsbPrinter.open(context, d)
                        try {
                            if (p == null) {
                                "无法打开打印机"
                            } else {
                                // 和 PrintService.recoverPrinter 保持同一套顺序。
                                val sr = p.softReset()
                                Thread.sleep(500)
                                val reopened = p.reopen()
                                Thread.sleep(300)
                                val uel = p.sendUel()
                                "已尝试 USB 复位（SOFT_RESET=${if (sr) "成功" else "失败"}，" +
                                    "重开连接=${if (reopened) "成功" else "失败"}，" +
                                    "UEL=${if (uel) "成功" else "失败"}）。" +
                                    "如果仍然打不出来，把打印机电源关掉再开。"
                            }
                        } finally {
                            p?.close()
                        }
                    }
                }
            }
            busy = null
            say(msg)
        }
    }

    // ── 队列 ────────────────────────────────────────────────────────────────

    fun setQueuePaused(paused: Boolean) {
        PrintService.instance?.jobsOrNull()?.setPaused(paused)
        say(if (paused) "队列已暂停" else "队列已恢复")
    }

    fun cancelJob(id: Int) {
        val ok = PrintService.instance?.jobsOrNull()?.cancel(id) == true
        say(if (ok) "已请求取消 #$id（正在打印的作业会打完当前页）" else "无法取消 #$id")
    }

    fun retryJob(id: Int) {
        val job = PrintService.instance?.jobsOrNull()?.retry(id)
        say(if (job != null) "已重新排队 #${job.id}" else "无法重试：文档已不在缓存里")
    }

    fun clearHistory() {
        PrintService.instance?.jobsOrNull()?.clearHistory()
        say("历史已清空")
    }

    fun approveClient(ip: String) {
        updateCfg { it.copy(approvedClients = it.approvedClients + ip) }
        ClientRegistry.entries().firstOrNull { it.ip == ip }?.blocked = false
        say("已允许 $ip 接入")
    }

    fun revokeClient(ip: String) {
        updateCfg { it.copy(approvedClients = it.approvedClients - ip) }
        say("已移除 $ip 的授权")
    }

    fun markTonerChanged() {
        updateCfg { it.copy(tonerChangedAt = System.currentTimeMillis()) }
        say("已记录粉盒更换时间")
    }

    /**
     * 手动指定打印机型号（空串 = 回到自动识别）。
     *
     * 只在「自动识别不出来」时才需要用户介入：有些 OEM 贴牌机的 VID/PID 不在表里，
     * 但机芯就是 brlaser 那一套。改完要通知服务重配广播 —— mDNS TXT 的 `ty` 与
     * IPP 的 printer-make-and-model 都取自型号名，两边必须同时变。
     */
    fun setPrinterModel(id: String) {
        updateCfg { it.copy(printerModelId = id) }
        val m = PrinterModel.byId(id)
        say(
            when {
                m == null -> "已切换为自动识别"
                // 直通换了驱动路径，光说「已指定型号」会让人以为只是改了个名字。
                m.driver == PrinterModel.Driver.PASSTHROUGH ->
                    "已切换为通用直通：不渲染，原样转发电脑上厂商驱动排好的数据"
                else -> "已指定型号：${m.name}"
            }
        )
        PrintService.reload(context)
    }

    /**
     * 改对外名称（mDNS 实例名 + IPP `printer-name`）。
     *
     * 传空串 = 恢复默认。这里存"空"而不是存默认名，是为了以后改默认名时
     * 恢复过默认的人能跟着一起变。
     *
     * 改完必须让服务重配广播：服务名是注册那一刻写进 PTR/SRV 的，改配置字符串
     * 对已经在跑的 mDNS 没有任何影响。
     */
    fun setPrinterName(raw: String) {
        val cleaned = AppSettings.normalizePrinterName(raw)
        // 输入的就是默认名 → 存空，等价于"没自定义过"。
        val stored = if (cleaned == AppSettings.DEFAULT_PRINTER_NAME) "" else cleaned
        updateCfg { it.copy(printerName = stored) }
        val shown = AppSettings.normalizePrinterName(stored)
            .ifBlank { AppSettings.DEFAULT_PRINTER_NAME }
        PrintService.reload(context)
        say(
            if (cfg.shareEnabled) "已改名为「$shown」，正在重发广播（客户端可能需要重新添加）"
            else "已改名为「$shown」，下次启动共享时生效"
        )
    }

    fun markWizardDone() {
        updateCfg { it.copy(wizardDone = true) }
    }

    fun restartWizard() {
        updateCfg { it.copy(wizardDone = false) }
    }

    // ── 系统入口 ────────────────────────────────────────────────────────────

    fun copy(label: String, text: String) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            say("已复制$label")
        }
    }

    fun requestUsbPermission() {
        scope.launch {
            val d = UsbPrinter.findSupportedPrinter(context)
            if (d == null) {
                say("没有检测到打印机")
                return@launch
            }
            val ok = UsbPrinter.requestPermission(context, d)
            say(if (ok) "USB 权限已授予" else "USB 权限被拒绝")
        }
    }

    fun openWifiSettings() = openSystem(Intent(Settings.ACTION_WIFI_SETTINGS))

    fun openHotspotSettings() =
        openSystem(Intent("android.settings.TETHER_SETTINGS"))

    fun openNotificationSettings() = openSystem(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    )

    /** 电池优化白名单。Android 6+ 的系统弹窗，比让用户自己在设置里找可靠。 */
    fun requestIgnoreBattery() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
        if (!openSystem(intent)) {
            openSystem(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    fun openAppDetails() = openSystem(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
    )

    private fun openSystem(intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrElse {
        say("系统里没有这个设置页：${it.message}")
        false
    }

    /** 是否已在电池优化白名单里（决定保活提示怎么显示）。 */
    fun isIgnoringBattery(): Boolean = runCatching {
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

    fun notificationsEnabled(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
    }.getOrDefault(false)

    fun requestNotifications() {
        val a = context as? android.app.Activity ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            a.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0x5751)
        }
    }

    companion object {
        @Composable
        fun remember(): UiController {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            return remember(context) { UiController(context, scope) }
        }
    }
}

/**
 * 首次进入时恢复上次的共享意图（用户开着共享关掉了 App）。
 *
 * **不再要求 `device.connected && device.permission`**。踩过的坑：USB 权限在重装
 * APK 后会被撤销，`connected` 也会在枚举的瞬间为 false；按旧条件就会「明明上次是
 * 开着共享的，这次打开却不恢复」，用户看到的是「打印机从列表里消失了」。
 *
 * 起不起来的后果由服务自己承担（`connectedDevice` 类型抛异常时退到 `specialUse`），
 * 而**不起**的后果是局域网里彻底没有这条广播 —— 后者严重得多。
 */
@Composable
fun AutoResumeShare(controller: UiController, runtime: Runtime) {
    LaunchedEffect(runtime.device.connected, runtime.device.permission, runtime.running) {
        val cfg = controller.cfg
        if (cfg.shareEnabled && cfg.wizardDone && !runtime.running) {
            PrintService.start(controller.context)
        }
    }
}
