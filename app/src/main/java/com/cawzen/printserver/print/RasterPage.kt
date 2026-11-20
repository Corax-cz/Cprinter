package com.cawzen.printserver.print

import android.util.Log
import com.cawzen.printserver.PrintOptions
import com.cawzen.printserver.usb.UsbPrinter
import java.io.File
import java.util.zip.CRC32
import kotlin.math.abs

/**
 * 一页「客户端已经光栅化好」的位图。0 = 黑，255 = 白，行优先。
 *
 * PWG Raster 与 URF 解码器都产出这个结构，之后的编码、双面补白、USB 写入完全一样，
 * 所以这段公共骨架抽在 [RasterPrint] 里 —— 两条路径的作业生命周期（begin/addPage/end、
 * 双面补白、失败要 abort）必须逐字节一致，各写一份迟早会漂移。
 */
data class RasterPage(
    val width: Int,
    val height: Int,
    val dpiX: Int,
    val dpiY: Int,
    val gray: ByteArray,
    /**
     * 客户端在**文档头**里声明的双面意图。
     *
     * 只有 URF 有这个概念（页头 `duplex` 字段：1=单面 2=长边 3=短边），
     * PDF / PWG 路径为 null，表示「本格式不表态，听界面的」。
     *
     * 为什么必须读它：iOS 走单段式 Print-Job，双面意图**同时**出现在 IPP 的
     * `sides` 属性和 URF 页头里。后者是 iOS 实际用来生成页面内容的依据，
     * 是客户端意图的第一手证据；`sides` 一旦解析不到（不同 iOS 版本属性组
     * 位置不一致），只看 IPP 就会把双面作业打成两张单面，而且不报任何错。
     */
    val duplexHint: Boolean? = null,
)

/** 解码器能自己说清楚的错误：消息里必须带页码和实际读到的值。 */
open class RasterFormatException(message: String) : Exception(message)

internal object RasterPrint {

    private const val TAG = "WPrint/Raster"

    /**
     * 打印机（HL-2240D 机芯）真实支持的光栅分辨率。
     *
     * **只有一个 600。** 这台机器的规格是「2400×600 dpi」—— 2400 是纵向抖动插值
     * 出来的观感，可选的光栅采样率只有 600 一档，没有 300dpi 模式。
     *
     * 以前这里采信文档页头给的任何 300/600，理由是「别把 300 放大 4 倍变糊」。
     * 那个理由在 PWG 客户端上成立，但对这台机芯是错的：按 300 编码发出去，
     * `@PJL SET RESOLUTION = 300` 不被接受，打印机把整页光栅丢掉，
     * 纸面上的表现是**一张纯白纸** —— 而且不报错、不走异常，最难查。
     *
     * 宁可让 300dpi 的文档重采样到 600 网格（最近邻，清晰度不会变好，但至少
     * 打得出来），也不能把页数正确的数据整页丢掉。
     */
    private const val PRINTER_DPI = 600

    /**
     * 「把一串已经解好的页面打成纸」的公共流程。
     *
     * [decode] 负责把页面推给 emit —— 推完一个就可以被回收，所以峰值只有一页
     * （600dpi A4 灰度 34.8MB）。这个设计不是洁癖：多页文件整读进内存必然 OOM。
     *
     * @param label 日志前缀，例如 "PWG Raster" / "URF"，同时用于区分是哪条路径。
     */
    fun execute(
        label: String,
        file: File,
        options: PrintOptions,
        printer: UsbPrinter,
        onLog: (String) -> Unit,
        decode: (emit: (RasterPage) -> Unit, log: (String) -> Unit) -> Unit,
    ): PdfPrint.Outcome {
        val sb = StringBuilder()
        fun line(s: String) {
            sb.append(s).append("\n")
            Log.i(TAG, "[$label] $s")
            onLog(s)
        }

        line("$label：${file.name}（${file.length()} 字节）")

        val paperSize = options.paperSize
        // 双面的最终取值在首页到达时才定：URF 页头能表态时以它为准（见下方说明）。
        var duplex = options.duplex
        var duplexSource = "界面设置"

        var handle = 0L
        var dpi = 0
        var paperW = 0
        var paperH = 0
        var rendered = 0
        val t0 = System.currentTimeMillis()
        try {
            decode(
                { page ->
                    if (handle == 0L) {
                        // 分辨率一律取 [PRINTER_DPI]，不采信文档页头。
                        //
                        // 这里踩过坑：URF 客户端（iOS）常按 300dpi 渲染，我们照 300 编，
                        // 发出去的 PJL 写着 `SET RESOLUTION = 300`，而 HL-2240D 机芯
                        // 没有这一档 —— 打印机把整页光栅扔掉，吐一张白纸，也不报错。
                        // 「文档多少 dpi 就按多少 dpi 打」听起来更省事也更清晰，
                        // 但前提是打印机支持那一档；这台机器只有 600。
                        dpi = PRINTER_DPI
                        val (w, h) = Paper.sizePx(paperSize, dpi)
                        paperW = w
                        paperH = h
                        // 文档头能表态时以文档头为准。
                        //
                        // 踩过的坑：iOS 选「双面」后，IPP 的 sides 属性解析不到
                        // （Print-Job 的属性组位置随版本有别），只剩 options.duplex=false，
                        // 于是两面各打一张单面 —— 纸出来了、页数也对，只有装订方式是错的，
                        // 属于「看起来成功」的失败。URF 页头里 iOS 明明写了 duplex=3。
                        val hint = page.duplexHint
                        if (hint != null && hint != duplex) {
                            duplex = hint
                            duplexSource = "URF 文档头"
                        }
                        line(
                            "目标：$paperSize ${paperW}x$paperH @${dpi}dpi duplex=$duplex" +
                                "（来源：$duplexSource）copies=${options.copies}" +
                                if (page.dpiX != PRINTER_DPI) {
                                    "；文档为 ${page.dpiX}dpi，已重采样到 ${PRINTER_DPI}dpi" +
                                        "（机芯只有这一档光栅模式）"
                                } else ""
                        )
                        handle = NativeBr.nativeJobBegin(dpi, duplex, options.copies, paperSize)
                        if (handle == 0L) throw RasterFormatException("nativeJobBegin 失败")
                    }

                    val idx = rendered + 1
                    val gray = page.fitToPaper(paperW, paperH) { line("  第 $idx 页：$it") }
                    // 8bpp 无压缩时，这个值与生成器的画布 CRC 应当逐字节相等 ——
                    // 是「解码器读对了」最硬的证据，比看纸面主观判断可靠。
                    val crc = CRC32().apply { update(gray) }.value
                    line("  第 $idx 页灰度 CRC32=%08X（%dx%d）".format(crc, paperW, paperH))
                    if (!NativeBr.nativeJobAddPageGray(handle, gray, paperW, paperH, paperW)) {
                        throw RasterFormatException("第 $idx 页编码被拒")
                    }
                    rendered++
                },
                { line("  $it") },
            )

            if (handle == 0L) return PdfPrint.Outcome(false, sb.append("文档里没有页面\n").toString())

            if (duplex && rendered % 2 == 1) {
                val blank = ByteArray(paperW * paperH) { 255.toByte() }
                if (NativeBr.nativeJobAddPageGray(handle, blank, paperW, paperH, paperW)) {
                    rendered++
                    line("双面补白页 → 共 $rendered 页")
                }
            }

            val stream = NativeBr.nativeJobEnd(handle)
            handle = 0L
            if (stream == null || stream.isEmpty()) {
                return PdfPrint.Outcome(false, sb.append("编码失败，输出为空\n").toString())
            }
            val t1 = System.currentTimeMillis()
            line("编码 ${t1 - t0} ms，输出 ${stream.size} 字节")

            // 把编码器的原始输出留一份。排查「打白纸」时这是唯一能定性的物证：
            // 字节流里有光栅块 → 编码没问题，锅在传输或打印机解读；只有 PJL 头和
            // 分页符 → 编码阶段就丢了内容。落盘成本只有几百 KB，比再打一张废纸便宜。
            val dump = File(file.parentFile, "last-encoded.pcl")
            runCatching {
                dump.writeBytes(stream)
                line("已落盘中间产物：${dump.name}（${dump.length()} 字节）")
            }.onFailure { line("中间产物落盘失败：${it.message}") }

            val written = printer.writeAll(stream)
            line("USB 写入 $written 字节，耗时 ${System.currentTimeMillis() - t1} ms")
            val ok = written == stream.size
            line(if (ok) "已发送，请检查出纸。" else "写入不完整。")
            return PdfPrint.Outcome(ok, sb.toString(), rendered)
        } catch (t: Throwable) {
            Log.e(TAG, "$label print failed", t)
            return PdfPrint.Outcome(
                false,
                sb.append("异常：${t.javaClass.simpleName}: ${t.message}\n").toString(),
                rendered,
            )
        } finally {
            if (handle != 0L) NativeBr.nativeJobAbort(handle)
        }
    }
}

/**
 * 把一页贴到目标尺寸上：同尺寸直接拷，不同则按比例最近邻缩放并居中。
 *
 * 为什么需要缩放：客户端有时按「可打印区域」而不是整页送数据（Windows 的 IPP 类驱动、
 * 某些 iOS App），直接贴会造成整页偏移。重采样至少保证内容居中且不出界。
 */
internal fun RasterPage.fitToPaper(
    paperW: Int,
    paperH: Int,
    onLog: (String) -> Unit = {},
): ByteArray {
    if (width == paperW && height == paperH) return gray

    // 差一两个像素是「取整基准不同」，不是真的尺寸不同：左上对齐贴过去，
    // 越过目标的裁掉、不足的留白即可。走缩放只会白白把整页糊掉。
    if (abs(width - paperW) <= 2 && abs(height - paperH) <= 2) {
        onLog("尺寸 ${width}x$height 与目标差 ≤2 像素，按左上贴合（不缩放）")
        val out = ByteArray(paperW * paperH) { 255.toByte() }
        val rows = minOf(height, paperH)
        val cols = minOf(width, paperW)
        for (y in 0 until rows) {
            System.arraycopy(gray, y * width, out, y * paperW, cols)
        }
        return out
    }

    onLog("尺寸 ${width}x$height 与目标 ${paperW}x$paperH 不同，重采样")
    val out = ByteArray(paperW * paperH) { 255.toByte() }
    val scale = minOf(paperW.toFloat() / width, paperH.toFloat() / height)
    val drawW = (width * scale).toInt().coerceAtLeast(1)
    val drawH = (height * scale).toInt().coerceAtLeast(1)
    val ox = (paperW - drawW) / 2
    val oy = (paperH - drawH) / 2

    for (y in 0 until drawH) {
        val sy = ((y + 0.5f) / scale).toInt().coerceIn(0, height - 1)
        val dy = oy + y
        if (dy < 0 || dy >= paperH) continue
        for (x in 0 until drawW) {
            val dx = ox + x
            if (dx < 0 || dx >= paperW) continue
            val sx = ((x + 0.5f) / scale).toInt().coerceIn(0, width - 1)
            out[dy * paperW + dx] = gray[sy * width + sx]
        }
    }
    return out
}
