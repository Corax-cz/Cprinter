package com.cawzen.printserver.print

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.cawzen.printserver.usb.UsbPrinter
import java.io.File

/**
 * Stage 2：PDF → 位图 → Native 编码 → USB。
 *
 * 全部依赖 `android.graphics.pdf.PdfRenderer`，不引入 MuPDF / Poppler。
 *
 * 三个必须遵守的约束（都是踩过或算过的）：
 *
 * 1. **Bitmap 必须是 ARGB_8888**。`PdfRenderer.Page.render` 只接受这个配置，
 *    RGB_565 会抛 IllegalArgumentException。所以 A4@600 一页就要 139MB
 *    (4961×7016×4) —— 直接整页渲染必 OOM，必须条带。
 *
 * 2. **渲染前必须 eraseColor(WHITE)**。PdfRenderer 不保证填充背景，透明区域
 *    转灰度后会变成黑色，整页反相。
 *
 * 3. **多页要进同一个 job**。用 [JobSession] 的流式接口，每页编码完即释放，
 *    峰值只跟单页挂钩，与页数无关。
 */
object PdfPrint {

    private const val TAG = "WPrint/Pdf"

    /** 单页 ARGB 位图超过这个量就走条带渲染。留足余量给灰度缓冲和编码器。 */
    private const val BAND_THRESHOLD_BYTES = 64L * 1024 * 1024

    private const val BAND_ROWS = 256

    /**
     * @param pages 真正写进作业的页数（含双面补白页），用于回填 IPP 的 job-impressions-completed。
     * @param cancelled 用户主动取消。与「失败」分开报，否则取消会被显示成打印机出错。
     */
    data class Outcome(
        val ok: Boolean,
        val report: String,
        val pages: Int = 0,
        val cancelled: Boolean = false,
    )

    data class RenderStats(
        val pages: Int,
        val renderedPages: Int,
        val widthPx: Int,
        val heightPx: Int,
        val peakBitmapBytes: Long,
        val banded: Boolean,
    )

    /**
     * 按界面当前选项打印（Stage 3 的 IPP 任务也走这个入口，保证网络任务与手动打印
     * 用的是同一套参数）。
     */
    fun print(
        printer: UsbPrinter,
        pdf: File,
        options: com.cawzen.printserver.PrintOptions,
        onLog: (String) -> Unit = {},
        shouldAbort: () -> Boolean = { false },
    ): Outcome = print(
        printer = printer,
        pdf = pdf,
        dpi = options.dpi,
        paperSize = options.paperSize,
        duplex = options.duplex,
        copies = options.copies,
        onLog = onLog,
        shouldAbort = shouldAbort,
    )

    /**
     * 打印一个 PDF 文件。
     *
     * @param duplex 双面。页数为奇数时自动补一页空白，否则打印机在等背面时会停住，
     *               最后一张纸要么卡在里面要么只印一面。
     * @param shouldAbort 每页开始前询问一次是否放弃。打印框架的取消只能这样落地：
     *                    从 `onLog` 里抛异常行不通，本函数会把任何 Throwable 当成
     *                    打印失败上报，取消就变成了"打印机出错"。
     */
    fun print(
        printer: UsbPrinter,
        pdf: File,
        dpi: Int = 300,
        paperSize: String = "A4",
        duplex: Boolean = false,
        copies: Int = 1,
        onLog: (String) -> Unit = {},
        shouldAbort: () -> Boolean = { false },
    ): Outcome {
        require(pdf.exists()) { "PDF 不存在：${pdf.absolutePath}" }

        val sb = StringBuilder()
        fun line(s: String) {
            sb.append(s).append("\n")
            Log.i(TAG, s)
            onLog(s)
        }

        val (paperW, paperH) = Paper.sizePx(paperSize, dpi)
        line("PDF：${pdf.name}（${pdf.length()} 字节）")
        line(
            "目标：A4 %dx%d @%ddpi duplex=%s copies=%d"
                .format(paperW, paperH, dpi, duplex, copies)
        )

        val pageArgbBytes = BitmapUtils.estimateBytes(paperW, paperH, Bitmap.Config.ARGB_8888)
        val banded = pageArgbBytes > BAND_THRESHOLD_BYTES
        line(
            "整页 ARGB 需 %.1f MB → %s".format(
                pageArgbBytes / 1048576.0,
                if (banded) "改走条带渲染（每带 $BAND_ROWS 行）" else "整页渲染",
            )
        )

        var handle = 0L
        val t0 = System.currentTimeMillis()
        try {
            val pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val count = renderer.pageCount
                    if (count <= 0) return Outcome(false, sb.append("PDF 没有页面\n").toString())
                    line("页数：$count")

                    handle = NativeBr.nativeJobBegin(dpi, duplex, copies, paperSize)
                    if (handle == 0L) return Outcome(false, sb.append("nativeJobBegin 失败\n").toString())

                    var rendered = 0
                    for (i in 0 until count) {
                        // 取消只在一页的边界上生效：写到一半停下会留下不完整的 PCL 流，
                        // 打印机会停在「等待数据」状态，比多印一页更麻烦。
                        if (shouldAbort()) {
                            line("用户取消，已编码 $rendered 页，未发送到打印机")
                            return Outcome(false, sb.toString(), rendered, cancelled = true)
                        }
                        renderer.openPage(i).use { page ->
                            val gray = renderPageToGray(page, paperW, paperH, banded) { msg ->
                                line("  第 ${i + 1} 页：$msg")
                            }
                            if (!NativeBr.nativeJobAddPageGray(handle, gray, paperW, paperH, paperW)) {
                                return Outcome(false, sb.append("第 ${i + 1} 页编码被拒\n").toString())
                            }
                            rendered++
                            line("  第 ${i + 1}/${count} 页完成（${page.width}x${page.height} pt）")
                        }
                    }

                    // 双面 + 奇数页：补一页空白。否则最后一张纸的背面无人认领，
                    // 打印机会停在「等待第二面」的状态。
                    if (duplex && rendered % 2 == 1) {
                        val blank = ByteArray(paperW * paperH) { 255.toByte() }
                        if (NativeBr.nativeJobAddPageGray(handle, blank, paperW, paperH, paperW)) {
                            rendered++
                            line("双面补白页 → 共 $rendered 页")
                        }
                    }

                    val inJob = NativeBr.nativeJobPageCount(handle)
                    line("作业内页数 = $inJob")
                    // 内存是这条链路最容易崩的地方（600dpi 整页 ARGB 要 139MB），
                    // 报出来才能证明条带路径真的把峰值压住了。
                    val rt = Runtime.getRuntime()
                    line(
                        "JVM 已用 %.0f MB / 上限 %.0f MB".format(
                            (rt.totalMemory() - rt.freeMemory()) / 1048576.0,
                            rt.maxMemory() / 1048576.0,
                        )
                    )

                    val stream = NativeBr.nativeJobEnd(handle)
                    handle = 0L
                    if (stream == null || stream.isEmpty()) {
                        return Outcome(false, sb.append("编码失败，输出为空\n").toString())
                    }
                    val t1 = System.currentTimeMillis()
                    line(
                        "编码 %d ms，输出 %d 字节（%.1f%% of 1bpp 原始大小）".format(
                            t1 - t0, stream.size,
                            stream.size * 100.0 / (paperW.toLong() * paperH / 8),
                        )
                    )

                    val written = printer.writeAll(stream) { done, total ->
                        if (done == total || done % (512 * 1024) == 0) {
                            Log.i(TAG, "USB $done / $total")
                        }
                    }
                    val t2 = System.currentTimeMillis()
                    line("USB 写入 %d 字节，耗时 %d ms".format(written, t2 - t1))
                    runCatching {
                        File(pdf.parentFile, pdf.nameWithoutExtension + ".pcl").writeBytes(stream)
                    }
                    val ok = written == stream.size
                    line(if (ok) "\n已发送，请对照 PDF 检查出纸。\n" else "\n写入不完整。\n")
                    return Outcome(ok, sb.toString(), rendered)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "print failed", t)
            return Outcome(false, sb.append("异常：${t.javaClass.simpleName}: ${t.message}\n").toString())
        } finally {
            if (handle != 0L) NativeBr.nativeJobAbort(handle)
        }
    }

    /**
     * 把一页渲染成灰度，铺满纸张（等比缩放 + 居中）。
     *
     * 走条带时每带只分配 `paperW × BAND_ROWS` 的 Bitmap，用 `destClip` + `transform`
     * 把该带的页面内容定位到带内 —— 这是 PdfRenderer 唯一支持的「局部渲染」手段，
     * 也是 600dpi 能跑起来的前提。
     */
    private fun renderPageToGray(
        page: PdfRenderer.Page,
        paperW: Int,
        paperH: Int,
        banded: Boolean,
        onLog: (String) -> Unit = {},
    ): ByteArray {
        // 页面尺寸以 pt（1/72 inch）计；换算成「每 pt 多少像素」。
        // 注意这里不需要 dpi 参数：paperW/H 已经包含了 dpi，scale 由两者比值决定。
        val scale = minOf(
            paperW.toFloat() / page.width,
            paperH.toFloat() / page.height,
        )
        val drawW = page.width * scale
        val drawH = page.height * scale
        val dx = (paperW - drawW) / 2f
        val dy = (paperH - drawH) / 2f

        val gray = ByteArray(paperW * paperH) { 255.toByte() }  // 纸底为白

        if (!banded) {
            val bmp = Bitmap.createBitmap(paperW, paperH, Bitmap.Config.ARGB_8888)
            try {
                bmp.eraseColor(Color.WHITE)  // 必须：否则透明区域转灰度后变黑
                val m = Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(dx, dy)
                }
                page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                System.arraycopy(BitmapUtils.toGray(bmp), 0, gray, 0, gray.size)
            } finally {
                bmp.recycle()
            }
            return gray
        }

        // 条带：每带只碰自己那几行，峰值与页高无关。
        val bandBmp = Bitmap.createBitmap(paperW, BAND_ROWS, Bitmap.Config.ARGB_8888)
        try {
            val clip = Rect(0, 0, paperW, BAND_ROWS)
            var y = 0
            while (y < paperH) {
                val rows = minOf(BAND_ROWS, paperH - y)
                bandBmp.eraseColor(Color.WHITE)
                val m = Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(dx, dy - y)  // 把第 y 行挪到带顶
                }
                if (rows == BAND_ROWS) {
                    page.render(bandBmp, clip, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                } else {
                    page.render(
                        bandBmp, Rect(0, 0, paperW, rows), m,
                        PdfRenderer.Page.RENDER_MODE_FOR_PRINT,
                    )
                }
                val bandGray = BitmapUtils.toGray(bandBmp)
                System.arraycopy(bandGray, 0, gray, y * paperW, rows * paperW)
                y += rows
            }
        } finally {
            bandBmp.recycle()
        }
        onLog("条带渲染完成（每带 $BAND_ROWS 行）")
        return gray
    }
}
