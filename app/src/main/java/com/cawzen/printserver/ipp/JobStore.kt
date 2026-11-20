package com.cawzen.printserver.ipp

import android.content.Context
import android.util.Log
import com.cawzen.printserver.PrintOptions
import com.cawzen.printserver.Stage0
import com.cawzen.printserver.print.PdfPrint
import com.cawzen.printserver.usb.UsbGate
import com.cawzen.printserver.usb.UsbPrinter
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 作业队列。串行是必须的，不是保守选择：USB 只有一个 Bulk OUT 端点、只能有一个写者，
 * 两个作业并行写端点只会得到交错垃圾字节，既浪费纸也可能让打印机状态机卡住。
 *
 * 设计取舍：
 * - **收到就先落盘再返回**。Print-Job 的语义允许异步，但必须在返回前把文档存好，
 *   否则 HTTP 连接一断、内存里的 body 就没了，客户端却已经看到 successful-ok。
 * - **每页决策用作业提交那一刻的设置**。用户在队列跑着的时候改画质，不该影响
 *   已经在排队的作业——否则同一份文档前后页可能不同 dpi，而 PCL 头只发一次。
 * - **单页失败不吞掉**。失败写进 job-state-reasons 和 message，客户端
 *   Get-Job-Attributes 能查到，界面也能看到。
 */
class JobStore(
    private val context: Context,
    private val optionsProvider: () -> PrintOptions,
    private val onUpdate: (String) -> Unit = {},
) {

    data class Job(
        val id: Int,
        val name: String,
        val user: String,
        @Volatile var format: String,
        /** 嗅探出的真实类型：PDF 走渲染管线，PCL 直通。见 DocumentSniffer。 */
        @Volatile var kind: com.cawzen.printserver.print.DocumentSniffer.Kind,
        @Volatile var file: File,
        val createdAt: Long,
        val dpi: Int,
        val duplex: Boolean,
        /** 份数，来自客户端 `copies`。见 [resolveCopies]。 */
        val copies: Int = 1,
        /**
         * 文档是否已经到手。
         *
         * AirPrint 客户端常走 Create-Job + Send-Document：作业先建好、数据后到，
         * 中间有一段「作业存在但没有文档」的窗口。这段既不能算进 queued-count
         * （打印机状态会一直显示忙），也不能进队列（worker 拿到会读到空文件）。
         */
        @Volatile var documentReady: Boolean = true,
        @Volatile var state: Int = Ipp.JOB_STATE_PENDING,
        @Volatile var reasons: String = "none",
        @Volatile var impressions: Int = 0,
        @Volatile var message: String = "",
        /** 提交者 IP（本机测试页为空串）。界面「已连接设备」与历史都靠它归因。 */
        val client: String = "",
        /** 结束时间（0 = 还没结束）。 */
        @Volatile var finishedAt: Long = 0L,
    )

    /**
     * 一条**已完成**作业的持久记录。
     *
     * 为什么要在内存作业表之外再存一份：作业对象活不过进程（前台服务被杀、
     * 手机重启），而用户第二天想知道的恰恰是「昨天那份文档到底打没打出来」。
     * 只存摘要，不存文档本身 —— 文档在缓存目录，一天后就被清了。
     */
    data class HistoryEntry(
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
        /** 失败原因摘要（成功时为空）。 */
        val message: String,
        val filePath: String,
    )

    private val tag = "WPrint/Jobs"
    private val MAX_HISTORY = 200
    private val lock = Any()
    private val jobs = LinkedHashMap<Int, Job>()
    private val nextId = AtomicInteger(1)
    private val queue = Channel<Int>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var activeJobId: Int = 0
        private set

    /**
     * 最近一次读到的打印机端口状态（人可读）。
     *
     * 为什么要把这个字符串存下来：USB 设备是独占的，网页/界面不可能为了显示状态
     * 去 open 一遍设备（会和正在跑的作业抢接口）。所以状态在每次开设备的瞬间
     * 顺手读一次并缓存 —— 「缺纸」「卡纸」这类硬件原因必须能在不打印的时候看到，
     * 否则用户唯一的线索就是一张白纸。
     */
    @Volatile
    var lastPortStatus: String = "尚未读取（还没有打印过）"
        private set

    private val jobsDir: File = File(context.cacheDir, "jobs").apply { mkdirs() }
    private val historyFile: File = File(context.filesDir, "print_history.json")

    /** 队列暂停位。暂停时已排队的作业原地等待，不丢、不改顺序。 */
    @Volatile
    var paused: Boolean = false
        private set

    private val historyLock = Any()
    private val history = ArrayList<HistoryEntry>()

    init {
        // 上次运行遗留的文档：留着只是为了排查，超过一天就删，避免占满缓存。
        runCatching {
            val cutoff = System.currentTimeMillis() - 24 * 3600_000L
            jobsDir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        }
        loadHistory()
        scope.launch {
            for (id in queue) {
                // 暂停只在「开始下一件」这一刻生效。正在写的作业不打断 ——
                // 中途停下会留下一段不完整的 PCL 流，打印机只会一直吐白纸。
                while (paused) delay(200)
                runJob(id)
            }
        }
    }

    /** 供界面与状态页展示的队列快照。 */
    fun snapshot(): List<Job> = synchronized(lock) { jobs.values.toList() }

    fun get(id: Int): Job? = synchronized(lock) { jobs[id] }

    fun queuedCount(): Int = synchronized(lock) {
        jobs.values.count {
            it.documentReady &&
                (it.state == Ipp.JOB_STATE_PENDING || it.state == Ipp.JOB_STATE_PROCESSING)
        }
    }

    /** 最近一个作业（状态页显示「上一条」用）。 */
    fun last(): Job? = synchronized(lock) { jobs.values.lastOrNull() }

    // ── 队列控制 / 历史 ─────────────────────────────────────────────────────

    fun setPaused(value: Boolean) {
        if (paused == value) return
        paused = value
        onUpdate(if (value) "队列已暂停（当前作业会先打完）" else "队列已恢复")
    }

    /** 历史记录，最新的在前。 */
    fun history(): List<HistoryEntry> = synchronized(historyLock) { history.toList() }

    fun clearHistory() {
        synchronized(historyLock) { history.clear() }
        runCatching { historyFile.delete() }
    }

    /**
     * 重试一次失败/取消的作业。
     *
     * 文档还在（缓存目录一天内不会清）就重新入队，参数沿用原作业 —— 尤其
     * 是 dpi 与单双面：用户重试的是「刚才那份」，不是「按现在界面的设置再打一份」。
     * 返回 null 表示文档已经没了或者作业本来就不该重试（成功的、正在打的）。
     */
    fun retry(id: Int): Job? {
        val live = synchronized(lock) { jobs[id] }
        val (name, user, format, kind, src, dpi, duplex, copies, client) = when {
            live != null && live.state != Ipp.JOB_STATE_COMPLETED -> RetrySpec(
                live.name, live.user, live.format, live.kind, live.file,
                live.dpi, live.duplex, live.copies, live.client,
            )
            else -> {
                val h = synchronized(historyLock) { history.firstOrNull { it.id == id } }
                    ?: return null
                val k = kindOf(h.kind)
                RetrySpec(
                    h.name, h.user,
                    if (k == com.cawzen.printserver.print.DocumentSniffer.Kind.PCL) {
                        "application/octet-stream"
                    } else "application/pdf",
                    k, File(h.filePath), PrintOptions.load(context).dpi, false, h.copies, h.client,
                )
            }
        }
        if (!src.exists() || src.length() == 0L) return null

        val newId = nextId.getAndIncrement()
        val safe = safeName(name, newId)
        val target = File(jobsDir, "%04d-%s.%s".format(newId, safe, extFor(kind)))
        runCatching { src.copyTo(target, overwrite = true) }.getOrElse { return null }
        val job = Job(
            id = newId,
            name = safe,
            user = user,
            format = format,
            kind = kind,
            file = target,
            createdAt = System.currentTimeMillis(),
            dpi = dpi,
            duplex = duplex,
            copies = copies.coerceIn(PrintOptions.MIN_COPIES, PrintOptions.MAX_COPIES),
            client = client,
        )
        synchronized(lock) { jobs[newId] = job }
        onUpdate("作业 #$newId 已重试（原 #$id）")
        queue.trySend(newId)
        return job
    }

    private data class RetrySpec(
        val name: String,
        val user: String,
        val format: String,
        val kind: com.cawzen.printserver.print.DocumentSniffer.Kind,
        val file: File,
        val dpi: Int,
        val duplex: Boolean,
        val copies: Int,
        val client: String,
    )

    private fun kindOf(label: String): com.cawzen.printserver.print.DocumentSniffer.Kind =
        when (label) {
            "PCL/PJL" -> com.cawzen.printserver.print.DocumentSniffer.Kind.PCL
            "PWG Raster" -> com.cawzen.printserver.print.DocumentSniffer.Kind.PWG_RASTER
            "URF (Apple Raster)" -> com.cawzen.printserver.print.DocumentSniffer.Kind.URF
            "PostScript" -> com.cawzen.printserver.print.DocumentSniffer.Kind.POSTSCRIPT
            else -> com.cawzen.printserver.print.DocumentSniffer.Kind.PDF
        }

    /** 作业走到终态时落一条历史（幂等：finishedAt 已置就跳过）。 */
    private fun recordHistory(job: Job) {
        if (job.finishedAt == 0L) job.finishedAt = System.currentTimeMillis()
        val entry = HistoryEntry(
            id = job.id,
            name = job.name,
            user = job.user,
            client = job.client,
            kind = describe(job.kind),
            state = job.state,
            reasons = job.reasons,
            pages = job.impressions,
            copies = job.copies,
            bytes = runCatching { job.file.length() }.getOrDefault(0L),
            createdAt = job.createdAt,
            finishedAt = job.finishedAt,
            message = job.message.take(400),
            filePath = job.file.absolutePath,
        )
        synchronized(historyLock) {
            history.removeAll { it.id == entry.id && it.createdAt == entry.createdAt }
            history.add(0, entry)
            while (history.size > MAX_HISTORY) history.removeAt(history.size - 1)
        }
        persistHistory()
    }

    private fun loadHistory() {
        val text = runCatching { historyFile.readText() }.getOrNull() ?: return
        runCatching {
            val arr = JSONArray(text)
            synchronized(historyLock) {
                history.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    history.add(
                        HistoryEntry(
                            id = o.optInt("id"),
                            name = o.optString("name"),
                            user = o.optString("user"),
                            client = o.optString("client"),
                            kind = o.optString("kind"),
                            state = o.optInt("state"),
                            reasons = o.optString("reasons"),
                            pages = o.optInt("pages"),
                            copies = o.optInt("copies", 1),
                            bytes = o.optLong("bytes"),
                            createdAt = o.optLong("createdAt"),
                            finishedAt = o.optLong("finishedAt"),
                            message = o.optString("message"),
                            filePath = o.optString("filePath"),
                        )
                    )
                }
            }
        }.onFailure { Log.w(tag, "历史记录读取失败：${it.message}") }
    }

    private fun persistHistory() {
        val arr = JSONArray()
        synchronized(historyLock) {
            history.forEach { e ->
                arr.put(
                    JSONObject().apply {
                        put("id", e.id)
                        put("name", e.name)
                        put("user", e.user)
                        put("client", e.client)
                        put("kind", e.kind)
                        put("state", e.state)
                        put("reasons", e.reasons)
                        put("pages", e.pages)
                        put("copies", e.copies)
                        put("bytes", e.bytes)
                        put("createdAt", e.createdAt)
                        put("finishedAt", e.finishedAt)
                        put("message", e.message)
                        put("filePath", e.filePath)
                    }
                )
            }
        }
        runCatching { historyFile.writeText(arr.toString()) }
            .onFailure { Log.w(tag, "历史记录写入失败：${it.message}") }
    }

    fun cancel(id: Int): Boolean {
        val job = synchronized(lock) { jobs[id] } ?: return false
        if (job.state == Ipp.JOB_STATE_COMPLETED) return false
        // 正在写的作业无法安全中断（写到一半停会让 PCL 流不完整），
        // 只标记，等它自然结束；排队中的立刻作废。
        if (job.state == Ipp.JOB_STATE_PENDING) {
            job.state = Ipp.JOB_STATE_CANCELED
            job.reasons = "job-canceled-by-user"
            onUpdate("作业 #$id 已取消")
        } else {
            job.reasons = "job-canceled-by-user"
            onUpdate("作业 #$id 正在打印，无法中断，已标记取消")
        }
        return true
    }

    /**
     * 网络作业的双面取值。
     *
     * 客户端没表态（null）时按 IPP 的缺省值 **单面** 走，不看界面设置。
     *
     * 这里踩过坑：原来是 `duplex ?: opts.duplex`，于是 iPhone 上明明选了
     * 「双面」，只因为手机界面底部那个「单/双面」停在「单面」，作业就继承了
     * 界面值变成单面 —— 出来两张单面纸，页数对、内容也对，**只有装订方式是错的**，
     * 而且全程不报错。用户只会以为打印机坏了。
     *
     * 界面上的「单/双面」是给「系统打印（手机）」和「测试页」这类本机发起的
     * 手动打印用的；网络作业的意图必须只来自客户端自己。
     * 客户端的意图还有第二个来源 —— URF 页头，见 [RasterPage.duplexHint]。
     */
    private fun resolveDuplex(fromClient: Boolean?): Boolean = fromClient ?: false

    /**
     * 网络作业的份数。客户端没表态按 1 份。
     *
     * 收窄到 [PrintOptions.MIN_COPIES]..[PrintOptions.MAX_COPIES]：`copies` 是
     * 网络来的整数，客户端完全可能送 0、负数或者 5000。0 在 PCL 里无法表达
     * （`ESC&l0X` 被 brlaser 兜成 1），但 5000 会真的连打 5000 份。
     */
    private fun resolveCopies(fromClient: Int?): Int =
        (fromClient ?: 1).coerceIn(PrintOptions.MIN_COPIES, PrintOptions.MAX_COPIES)

    /**
     * 接收一个文档并排队。**返回即代表文档已落盘**。
     *
     * @param duplex 客户端通过 `sides` 指定的。null 表示客户端没表态，
     *   此时按 IPP 的缺省值（`one-sided`）走，**不继承界面设置**。
     *   理由见 [resolveDuplex]。
     */
    fun submit(
        name: String,
        user: String,
        format: String,
        kind: com.cawzen.printserver.print.DocumentSniffer.Kind,
        documentFile: File,
        duplex: Boolean?,
        copies: Int? = null,
        /** 提交者 IP。IPP 请求线程通过 [ClientRegistry] 的 ThreadLocal 带进来。 */
        client: String = ClientRegistry.currentClient(),
    ): Job {
        val id = nextId.getAndIncrement()
        val opts = optionsProvider()
        val safe = safeName(name, id)
        val job = Job(
            id = id,
            name = safe,
            user = user.ifBlank { "anonymous" },
            format = format,
            kind = kind,
            file = File(jobsDir, "%04d-%s.%s".format(id, safe, extFor(kind))),
            createdAt = System.currentTimeMillis(),
            dpi = opts.dpi,
            duplex = resolveDuplex(duplex),
            copies = resolveCopies(copies),
            client = client,
        )
        moveInto(documentFile, job.file)
        synchronized(lock) { jobs[id] = job }
        if (client.isNotBlank()) ClientRegistry.recordJob(client)
        Log.i(tag, "接收作业 #$id ${job.file.name}（${job.file.length()} 字节，${describe(kind)}）")
        onUpdate("收到作业 #$id，排队中")
        queue.trySend(id)
        return job
    }

    /**
     * Create-Job（AirPrint 客户端常用这个流程，而不是一口气 Print-Job）。
     *
     * 只占一个作业号，不排队 —— 文档要等 Send-Document 到了才 [attach] 上去。
     * 这中间作业是「存在但没数据」的状态，见 [Job.documentReady]。
     */
    fun create(
        name: String,
        user: String,
        format: String,
        duplex: Boolean?,
        copies: Int? = null,
        client: String = ClientRegistry.currentClient(),
    ): Job {
        val id = nextId.getAndIncrement()
        val opts = optionsProvider()
        val safe = safeName(name, id)
        val job = Job(
            id = id,
            name = safe,
            user = user.ifBlank { "anonymous" },
            format = format,
            kind = com.cawzen.printserver.print.DocumentSniffer.Kind.UNKNOWN,
            // 还没数据，先给一个不会和真实文件撞名的占位路径。
            file = File(jobsDir, "%04d-%s.pending".format(id, safe)),
            createdAt = System.currentTimeMillis(),
            dpi = opts.dpi,
            duplex = resolveDuplex(duplex),
            copies = resolveCopies(copies),
            documentReady = false,
            client = client,
        )
        synchronized(lock) { jobs[id] = job }
        Log.i(tag, "建作业 #$id「${job.name}」（等文档）")
        onUpdate("作业 #$id 已建立，等待文档")
        return job
    }

    /**
     * Send-Document：把文档挂到已建好的作业上，落盘后立刻排队。
     *
     * 返回 false 表示作业不存在、已经拿到过文档（我们不支持一作业多文档）、
     * 或搬文件失败。调用方据此回 IPP 错误码，而不是假装成功。
     */
    fun attach(
        id: Int,
        documentFile: File,
        kind: com.cawzen.printserver.print.DocumentSniffer.Kind,
        format: String,
    ): Boolean {
        val job = synchronized(lock) { jobs[id] } ?: return false
        if (job.documentReady) {
            Log.w(tag, "作业 #$id 已经有文档，拒绝第二份（multiple-document-jobs-supported=false）")
            return false
        }
        val target = File(jobsDir, "%04d-%s.%s".format(id, job.name, extFor(kind)))
        moveInto(documentFile, target)
        job.kind = kind
        job.file = target
        job.format = format
        job.documentReady = true
        Log.i(tag, "作业 #$id 收到文档 ${target.name}（${target.length()} 字节，${describe(kind)}）")
        onUpdate("作业 #$id 收到文档，排队中")
        queue.trySend(id)
        return true
    }

    /** 把上传暂存搬进作业目录。同分区用 rename，跨分区才退回复制。 */
    private fun moveInto(documentFile: File, target: File) {
        if (documentFile.renameTo(target)) return
        Log.w(tag, "rename 失败，改用复制：${documentFile.absolutePath}")
        documentFile.copyTo(target, overwrite = true)
        documentFile.delete()
    }

    private fun safeName(name: String, id: Int): String =
        name.ifBlank { "job-$id" }
            .replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
            .take(40)

    private fun extFor(kind: com.cawzen.printserver.print.DocumentSniffer.Kind): String = when (kind) {
        com.cawzen.printserver.print.DocumentSniffer.Kind.PCL -> "pcl"
        com.cawzen.printserver.print.DocumentSniffer.Kind.PWG_RASTER -> "pwg"
        com.cawzen.printserver.print.DocumentSniffer.Kind.URF -> "urf"
        com.cawzen.printserver.print.DocumentSniffer.Kind.POSTSCRIPT -> "ps"
        else -> "pdf"
    }

    private fun describe(kind: com.cawzen.printserver.print.DocumentSniffer.Kind): String =
        com.cawzen.printserver.print.DocumentSniffer.describe(kind)

    private suspend fun runJob(id: Int) {
        val job = synchronized(lock) { jobs[id] } ?: return
        if (job.state == Ipp.JOB_STATE_CANCELED) {
            onUpdate("作业 #$id 已取消，跳过")
            recordHistory(job)
            return
        }

        job.state = Ipp.JOB_STATE_PROCESSING
        activeJobId = id
        onUpdate("作业 #$id 打印中")
        Log.i(tag, "开始作业 #$id")

        var printer: UsbPrinter? = null
        try {
            // 整条打印链路串行：从开设备到写完字节，中途不让第二个作业插进来。
            // 这把锁是进程级、跨组件的 —— Android 打印框架插件（UsbPrintPlugin）
            // 从系统打印界面收到的作业也要排队，否则两股字节流会交错写进同一个端点。
            // 阻塞等待在这里没问题：本函数跑在 Dispatchers.IO 上。
            UsbGate.withLock {
                val opened = Stage0.run(context, Stage0.Mode.EXCLUSIVE)
                Log.i(tag, opened.report)
                val p = opened.printer
                if (p == null) {
                    job.state = Ipp.JOB_STATE_ABORTED
                    job.reasons = "device-error"
                    job.message = "无法打开打印机：\n" + opened.report
                    onUpdate("作业 #$id 失败：打印机不可用")
                    return
                }
                printer = p

                // 调试开关：缓存目录下存在 `dryrun` 文件时，只生成字节流不发送。
                // 排查「纸面不对」时，这样可以在零耗纸的前提下反复验证渲染与编码。
                p.dryRun = File(context.cacheDir, "dryrun").exists()
                if (p.dryRun) Log.w(tag, "作业 #$id 处于 dryRun 模式：只编码，不写 USB")

                // 纸张/盖板这类硬件原因，和「数据没写进去」在纸面上长得一模一样。
                // 每次拿到设备就先读一次端口状态并留档，失败时至少能区分这两种情况。
                lastPortStatus = p.portStatusText()
                Log.i(tag, "作业 #$id 打印机端口状态：$lastPortStatus")
                // 缺纸 / 卡纸时**一个字节都不写**。
                //
                // 硬灌的后果实测过：写完前 512KB 之后 OUT 端点进 HALT，之后每次
                // bulkTransfer 都超时 5 秒，退避重试 3 轮，最终报「USB 写入失败」——
                // 缺纸这个真实原因被那句话完全掩盖，用户会去查线、查驱动、查端口，
                // 而纸上什么都没少（也没多），看不出任何线索。
                //
                // 早退的代价只是一个作业要手动重试，而它本来也打不出来。
                if (p.isPaperEmpty()) {
                    job.state = Ipp.JOB_STATE_ABORTED
                    job.reasons = "resources-are-not-ready"
                    job.message = "打印机缺纸（PaperEmpty），数据未发送。装好纸张后点「重试」。"
                    onUpdate("作业 #$id 未发送：打印机缺纸")
                    return
                }

                // ── 先定路径：渲染（兄弟机芯）还是直通（客户端已排好版）──────────
                //
                // 这两个模式不能混。渲染模式把 PDF/URF/PWG 画成兄弟机芯的 PCL；
                // 直通模式一个字节都不改，因为我们根本不知道对面是什么机芯。
                val kind = job.kind
                val passthrough = UsbPrinter.driverOf(context) ==
                    com.cawzen.printserver.usb.PrinterModel.Driver.PASSTHROUGH

                // 直通模式下收到 URF / PWG 是「客户端还没排版」的证据：那是 iOS 和
                // 没装厂商驱动的 Windows 送来的光栅，必须由我们画成目标机芯的语言，
                // 而通用直通不知道对方吃什么。把光栅直接灌进去只会出几十页乱码，
                // 还可能让打印机卡在半截流里，所以这里明确失败并给出下一步。
                if (passthrough &&
                    (kind == com.cawzen.printserver.print.DocumentSniffer.Kind.URF ||
                        kind == com.cawzen.printserver.print.DocumentSniffer.Kind.PWG_RASTER)
                ) {
                    job.state = Ipp.JOB_STATE_ABORTED
                    job.reasons = "document-format-error"
                    job.message =
                        "通用直通模式不生成光栅：这份作业是 ${com.cawzen.printserver.print.DocumentSniffer.describe(kind)}，" +
                            "来自 iPhone 或没装厂商驱动的 Windows。\n" +
                            "请用已安装厂商驱动的电脑指向 9101 端口打印；" +
                            "如果这台是兄弟机芯，把机型改回 Brother 系列即可支持 AirPrint。"
                    onUpdate("作业 #$id 被拒绝：直通模式不支持 ${com.cawzen.printserver.print.DocumentSniffer.describe(kind)}")
                    return
                }

                // 兄弟机芯只有自己的 PCL 解释器（HL-2240D 连 PostScript 都没有），
                // 把 PS 丢进 PdfRenderer 只会得到「文档损坏」，不如直接说清楚。
                if (!passthrough && kind == com.cawzen.printserver.print.DocumentSniffer.Kind.POSTSCRIPT) {
                    job.state = Ipp.JOB_STATE_ABORTED
                    job.reasons = "document-format-error"
                    job.message =
                        "这份作业是 PostScript，而当前机型是兄弟激光机芯（只认自家 PCL）。\n" +
                            "请在客户端改用 PDF，或把机型切成「通用 PCL / PostScript 打印机」走直通。"
                    onUpdate("作业 #$id 被拒绝：当前机芯不支持 PostScript")
                    return
                }

                // PCL 直通（Windows 厂商驱动走的就是这条）；直通模式下 PCL / PS /
                // PDF / 未识别格式一律原样转发 —— 那是客户端自己的驱动产出的字节，
                // 我们改一个字都可能打乱它的页面状态机。
                if (passthrough || kind == com.cawzen.printserver.print.DocumentSniffer.Kind.PCL) {
                    val raw = com.cawzen.printserver.print.RawPrint.print(
                        p, job.file,
                        kindLabel = if (passthrough) "直通" else "PCL 直通",
                    ) { line ->
                        Log.i(tag, "#$id $line")
                    }
                    // 不知道里面有几页（要解析 PCL/PS 才能知道），报 1 表示「已发出」。
                    job.impressions = 1
                    job.message = raw.report
                    if (raw.ok) {
                        job.state = Ipp.JOB_STATE_COMPLETED
                        job.reasons = "none"
                        onUpdate("作业 #$id 已完成（${if (passthrough) "直通" else "PCL 直通"}）")
                    } else {
                        job.state = Ipp.JOB_STATE_ABORTED
                        job.reasons = "job-completed-with-errors"
                        onUpdate("作业 #$id 失败")
                    }
                    return
                }

                val opts = optionsProvider().copy(
                    dpi = job.dpi,
                    duplex = job.duplex,
                    copies = job.copies,
                )
                val logger: (String) -> Unit = { line -> Log.i(tag, "#$id $line") }

                // 三条光栅路径（PWG / URF / PDF）的收尾逻辑完全一样，只是解码器不同：
                // PWG 来自没装厂商驱动的 Windows，URF 来自 AirPrint（iOS/macOS），
                // PDF 是通用路径。它们都返回同一个 Outcome，所以这里合并处理。
                val outcome = when (job.kind) {
                    com.cawzen.printserver.print.DocumentSniffer.Kind.PWG_RASTER ->
                        com.cawzen.printserver.print.PwgRaster.print(p, job.file, opts, logger)

                    com.cawzen.printserver.print.DocumentSniffer.Kind.URF ->
                        com.cawzen.printserver.print.Urf.print(p, job.file, opts, logger)

                    else -> PdfPrint.print(p, job.file, opts, onLog = logger)
                }
                // job-impressions 按 IPP 语义是**总印张数**，份数要乘进去：
                // 2 页 × 3 份 = 6 个 impression（双面则是 3 张纸的正反面）。
                job.impressions = outcome.pages * job.copies
                job.message = outcome.report
                if (outcome.ok) {
                    job.state = Ipp.JOB_STATE_COMPLETED
                    job.reasons = "none"
                    onUpdate(
                        "作业 #$id 完成（${outcome.pages} 页" +
                            (if (job.copies > 1) " × ${job.copies} 份" else "") +
                            "，${com.cawzen.printserver.print.DocumentSniffer.describe(job.kind)}）"
                    )
                } else {
                    job.state = Ipp.JOB_STATE_ABORTED
                    job.reasons = "job-completed-with-errors"
                    onUpdate("作业 #$id 失败")
                }
            }
        } catch (t: Throwable) {
            Log.e(tag, "作业 #$id 异常", t)
            job.state = Ipp.JOB_STATE_ABORTED
            job.reasons = "job-completed-with-errors"
            job.message = "${t.javaClass.simpleName}: ${t.message}"
            onUpdate("作业 #$id 异常：${t.message}")
        } finally {
            activeJobId = 0
            runCatching { printer?.close() }
            // 走到这里一定是终态（完成 / 失败 / 取消）。历史只在这一个地方落，
            // 避免「PCL 分支提前 return」这类路径漏记 —— 那条路径以前就是这样丢的。
            if (job.state == Ipp.JOB_STATE_COMPLETED ||
                job.state == Ipp.JOB_STATE_ABORTED ||
                job.state == Ipp.JOB_STATE_CANCELED
            ) {
                recordHistory(job)
            }
        }
    }

    fun shutdown() {
        runCatching { queue.close() }
        runCatching { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }
}
