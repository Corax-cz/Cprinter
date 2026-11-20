package com.cawzen.printserver.androidprint

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob as FwPrintJob
import android.printservice.PrintService as FwPrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import com.cawzen.printserver.PrintOptions
import com.cawzen.printserver.print.PdfPrint
import com.cawzen.printserver.usb.UsbGate
import com.cawzen.printserver.usb.UsbPrinter
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Android 打印框架插件（`android.printservice.PrintService`）。
 *
 * IPP 服务器解决的是「**别人**打过来」；要在 WPS、相册、Chrome、微信里点「打印」
 * 直接出纸，必须实现本类 —— 那是**手机自己**发起的打印。
 *
 * ## 送进来的是什么格式
 *
 * Android 的 Print Spooler 总是先把应用的内容渲染成 **PDF**，再把只读文件描述符
 * 交给打印服务。所以这里不需要关心上游是 Word、网页还是图片，拿到的永远是 PDF，
 * 直接复用 [PdfPrint] 那条已经验收过的管线。
 *
 * ## 线程模型（踩过，别改）
 *
 * `PrintJob` 的**每一个**方法（`getInfo`/`getDocument`/`start`/`fail`/`complete`/
 * `setStatus`/`setProgress`）内部都有 `throwIfNotCalledOnMainThread()`。在工作线程里
 * 调用不会抛普通异常，而是 `IllegalAccessError` —— 它是 Error 不是 Exception，
 * 落在 `catch (Exception)` 之外，直接导致进程被 SIGKILL。
 *
 * 所以：**主线程只做资料提取和状态回报，工作线程只做渲染和 USB 写。**
 * 进场时一次性取出 info / attributes / document FD，其余对 PrintJob 的调用都 post 回主线程。
 *
 * ## 为什么不在这里请求 USB 权限
 *
 * 打印作业在后台触发，此时用户可能已退出打印页；从后台
 * `UsbManager.requestPermission()` 弹出的对话框不保证可见，阻塞等待只会拖住队列。
 * 所以这里只做**检查**，缺权限就把指引写进作业状态（系统打印队列会显示），
 * 授权动作交给前台 —— `MainActivity` 启动时会自动申请一次。
 */
class UsbPrintPlugin : FwPrintService() {

    companion object {
        private const val TAG = "WPrint/Plugin"

        /** 本机只有一台打印机，localId 固定即可，不必拼序列号。 */
        private const val LOCAL_ID = "usb-lj2600d"

        private const val RES_600 = "res600"
        private const val RES_300 = "res300"

        private val PAGE_DONE = Regex("第 (\\d+)/(\\d+) 页完成")

        /** 打印服务对外声明的打印机名，动态取自 USB 设备型号。 */
        private fun printerName(context: android.content.Context): String {
            val device = UsbPrinter.findSupportedPrinter(context)
            return UsbPrinter.modelOf(context, device ?: return "Cprinter 打印机")?.displayName
                ?: "Cprinter 打印机"
        }
    }

    /** 单线程：作业必须串行，USB 只有一个写者。 */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wprint-plugin").apply { isDaemon = true }
    }

    private val main = Handler(Looper.getMainLooper())

    /** 已请求取消的作业 key。作业线程在每页边界检查。 */
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    override fun onConnected() {
        Log.i(TAG, "打印插件已连接到系统打印框架")
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = Discovery()

    override fun onPrintJobQueued(printJob: FwPrintJob) {
        // 本回调在主线程。PrintJob 的方法只能在主线程碰，所以在这里一次性把
        // 需要的东西全取出来，再把耗时的活交给工作线程。
        val key = printJob.info.id?.toString() ?: "?"
        Log.i(TAG, "作业入队 $key「${printJob.info.label}」")

        if (!printJob.isQueued) {
            Log.w(TAG, "作业 $key 不在排队状态，跳过")
            return
        }
        if (!printJob.start()) {
            Log.w(TAG, "作业 $key start() 返回 false，可能已被取消")
            return
        }

        val attrs = printJob.info.attributes
        val pfd: ParcelFileDescriptor
        try {
            val fd = printJob.document.data
            if (fd == null) {
                printJob.fail("打印框架没有给出文档数据（document.data 为 null）。")
                return
            }
            pfd = fd
        } catch (t: Throwable) {
            Log.e(TAG, "取文档失败", t)
            printJob.fail("无法从打印框架取得文档：${t.javaClass.simpleName}: ${t.message}")
            return
        }
        worker.execute { runJob(printJob, key, attrs, pfd) }
    }

    override fun onRequestCancelPrintJob(printJob: FwPrintJob) {
        val key = printJob.info.id?.toString() ?: return
        cancelled.add(key)
        Log.i(TAG, "请求取消作业 $key")
    }

    override fun onDestroy() {
        // 用 shutdown 而不是 shutdownNow：已在写的作业必须写完，
        // 半截的 PCL 流会让打印机停在「等待数据」状态。
        worker.shutdown()
        super.onDestroy()
    }

    // ── 打印机发现 ────────────────────────────────────────────────────────

    private inner class Discovery : PrinterDiscoverySession() {

        private val printerId: PrinterId by lazy { generatePrinterId(LOCAL_ID) }

        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
            addPrinters(buildPrinters())
        }

        override fun onStopPrinterDiscovery() = Unit

        /**
         * 系统偶尔会核对打印机是否还在。设备被拔掉时必须主动移除，
         * 否则列表里会留一个永远打不出来的幽灵打印机。
         */
        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
            if (UsbPrinter.findSupportedPrinter(this@UsbPrintPlugin) == null) {
                Log.i(TAG, "设备已移除，撤销打印机声明")
                removePrinters(printerIds)
            }
        }

        override fun onStartPrinterStateTracking(printerId: PrinterId) {
            if (printerId.localId != LOCAL_ID) return
            // 必须回应。系统在用户选中打印机后调这里确认状态，插件不回话，
            // 打印界面会直接显示「该打印机目前无法使用」，一个字节都发不出去。
            addPrinters(buildPrinters())
        }

        override fun onStopPrinterStateTracking(printerId: PrinterId) = Unit

        override fun onDestroy() = Unit

        private fun buildPrinters(): List<PrinterInfo> {
            val device = UsbPrinter.findSupportedPrinter(this@UsbPrintPlugin)
            if (device == null) {
                Log.i(TAG, "未发现受支持的 USB 打印机")
                return emptyList()
            }

            val hasPerm = UsbPrinter.manager(this@UsbPrintPlugin).hasPermission(device)
            val name = printerName(this@UsbPrintPlugin)
            val caps = PrinterCapabilitiesInfo.Builder(printerId)
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
                .addResolution(PrintAttributes.Resolution(RES_600, "600 dpi 细腻", 600, 600), true)
                .addResolution(PrintAttributes.Resolution(RES_300, "300 dpi 快", 300, 300), false)
                .setColorModes(
                    PrintAttributes.COLOR_MODE_MONOCHROME,
                    PrintAttributes.COLOR_MODE_MONOCHROME,
                )
                .setDuplexModes(
                    PrintAttributes.DUPLEX_MODE_NONE or PrintAttributes.DUPLEX_MODE_LONG_EDGE,
                    PrintAttributes.DUPLEX_MODE_NONE,
                )
                .build()

            val info = PrinterInfo.Builder(printerId, name, PrinterInfo.STATUS_IDLE)
                .setDescription(
                    if (hasPerm) "USB 直连 · 无需驱动 · 黑白激光"
                    else "尚未授权：请先打开「Cprinter」App 授予 USB 权限",
                )
                .setCapabilities(caps)
                .build()

            Log.i(TAG, "声明打印机「$name」权限=$hasPerm 设备=${device.deviceName}")
            return listOf(info)
        }
    }

    // ── 作业执行（工作线程）────────────────────────────────────────────────

    private fun runJob(
        printJob: FwPrintJob,
        key: String,
        attrs: PrintAttributes?,
        pfd: ParcelFileDescriptor,
    ) {
        var keepFile = false
        var file: File? = null
        try {
            val device = UsbPrinter.findSupportedPrinter(this)
            if (device == null) {
                failOnMain(printJob, "未检测到 USB 打印机。请确认打印机已插好并已开机。")
                return
            }
            if (!UsbPrinter.manager(this).hasPermission(device)) {
                failOnMain(
                    printJob,
                    "本 App 还没有 USB 访问权限。\n" +
                        "请打开「打印服务器」App，点一次「Stage 0 独占」，" +
                        "在弹出的系统对话框里允许访问该 USB 设备，然后再打印。",
                )
                return
            }

            statusOnMain(printJob, "正在接收文档…")
            val doc = saveDocument(pfd, key)
            file = doc
            keepFile = true

            if (!looksLikePdf(doc)) {
                val head = doc.inputStream().use { ins ->
                    ByteArray(minOf(16, doc.length().toInt())).also { ins.read(it) }
                }
                failOnMain(
                    printJob,
                    "打印框架送来的不是 PDF（头 16 字节：" +
                        head.joinToString(" ") { "%02X".format(it) } + "），无法处理。",
                )
                return
            }

            val opts = resolveOptions(attrs)
            Log.i(TAG, "作业 $key ${doc.length()} 字节 → ${opts.describe()}")

            UsbGate.withLock {
                val printer = UsbPrinter.open(this, device)
                if (printer == null) {
                    failOnMain(
                        printJob,
                        "无法打开 USB 打印机。若有别的程序（例如 Termux 里的打印服务）" +
                            "正占用这台设备，请先停掉它。",
                    )
                    return@withLock
                }
                try {
                    val outcome = PdfPrint.print(
                        printer = printer,
                        pdf = doc,
                        options = opts,
                        onLog = { line ->
                            Log.i(TAG, "$key $line")
                            reportProgress(printJob, line)
                        },
                        shouldAbort = { cancelled.contains(key) },
                    )
                    when {
                        outcome.cancelled -> {
                            cancelOnMain(printJob)
                            Log.i(TAG, "作业 $key 已取消（可能已有一两页出纸）")
                        }
                        outcome.ok -> {
                            keepFile = false
                            Log.i(TAG, "作业 $key 完成，${outcome.pages} 页")
                            completeOnMain(printJob)
                        }
                        else -> {
                            failOnMain(printJob, outcome.report.takeLast(600))
                            Log.e(TAG, "作业 $key 失败：${outcome.report.takeLast(200)}")
                        }
                    }
                } finally {
                    runCatching { printer.close() }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "作业 $key 异常", t)
            failOnMain(printJob, "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            cancelled.remove(key)
            // 成功就删掉文档；失败/取消的留着，方便从 cacheDir 取出来复现。
            if (!keepFile) runCatching { file?.delete() }
        }
    }

    private fun documentFile(key: String): File {
        val dir = File(cacheDir, "androidprint").apply { mkdirs() }
        return File(dir, "job-${key.replace(Regex("[^A-Za-z0-9_-]"), "_")}.pdf")
    }

    /** 把打印框架给的只读 FD 落成文件。`PdfRenderer` 需要可 seek 的 FD。 */
    private fun saveDocument(pfd: ParcelFileDescriptor, key: String): File {
        val file = documentFile(key)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            file.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
        }
        return file
    }

    private fun looksLikePdf(file: File): Boolean {
        if (file.length() < 5) return false
        return file.inputStream().use { ins ->
            val head = ByteArray(5)
            ins.read(head) == 5 && String(head, Charsets.ISO_8859_1) == "%PDF-"
        }
    }

    /**
     * 打印界面里用户可能改了分辨率或单/双面，以那里的选择为准；没改过就跟随
     * App 内的全局设置。纸张只有 A4 —— 这台打印机只吃 A4，不接受也无从选择。
     */
    private fun resolveOptions(attrs: PrintAttributes?): PrintOptions {
        val base = PrintOptions.load(this)
        if (attrs == null) return base

        val dpi = when (attrs.resolution?.id) {
            RES_300 -> 300
            RES_600 -> 600
            else -> base.dpi
        }
        val duplex = when (attrs.duplexMode) {
            PrintAttributes.DUPLEX_MODE_LONG_EDGE,
            PrintAttributes.DUPLEX_MODE_SHORT_EDGE,
            -> true
            PrintAttributes.DUPLEX_MODE_NONE -> false
            else -> base.duplex
        }
        return base.copy(dpi = dpi, duplex = duplex, paperSize = PrintOptions.PAPER_A4)
    }

    /** PdfPrint 只回调文本日志，顺手解析页进度，免得系统通知里看着像假死。 */
    private fun reportProgress(printJob: FwPrintJob, line: String) {
        if (line.contains("USB 写入")) {
            statusOnMain(printJob, "正在发送到打印机…")
            return
        }
        val m = PAGE_DONE.find(line) ?: return
        val done = m.groupValues[1].toIntOrNull() ?: return
        val total = m.groupValues[2].toIntOrNull() ?: return
        if (total <= 0) return
        val fraction = 0.05f + 0.9f * done / total
        main.post {
            runCatching {
                printJob.setProgress(fraction)
                printJob.setStatus("正在渲染第 $done / $total 页")
            }
        }
    }

    // PrintJob 的状态回报必须回主线程，见类注释里的线程模型。

    private fun statusOnMain(printJob: FwPrintJob, text: String) {
        main.post { runCatching { printJob.setStatus(text) } }
    }

    private fun completeOnMain(printJob: FwPrintJob) {
        main.post {
            runCatching {
                printJob.setProgress(1f)
                printJob.complete()
            }
        }
    }

    private fun failOnMain(printJob: FwPrintJob, message: String) {
        main.post { runCatching { printJob.fail(message) } }
    }

    private fun cancelOnMain(printJob: FwPrintJob) {
        main.post { runCatching { printJob.cancel() } }
    }
}
