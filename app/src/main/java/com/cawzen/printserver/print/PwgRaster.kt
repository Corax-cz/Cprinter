package com.cawzen.printserver.print

import com.cawzen.printserver.usb.UsbPrinter
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

/**
 * PWG Raster（PWG 5102.4）解码 —— 第三条文档路径。
 *
 * 为什么需要它：Windows 上如果没装厂商驱动，添加 IPP 打印机时会被分配
 * 「Microsoft IPP Class Driver」，它**不会**发 PDF，而是发 PWG Raster。
 * 只声明 PDF 的话这类客户端要么装不上，要么打出来是废纸。
 *
 * 文件结构（每页同构，没有独立的文件头）：
 *
 * ```
 * [4 字节同步字 "RaS2"] [1796 字节 cups_page_header2_t] [height × bytesPerLine 像素]
 * [4 字节同步字 "RaS2"] [1796 字节 cups_page_header2_t] [height × bytesPerLine 像素]
 * ```
 *
 * 同步字既是文件魔数也是页分隔符 —— 所以 DocumentSniffer 看到 "RaS2" 就能定性。
 * 所有字段都是**大端**。
 *
 * 只支持无压缩（cupsCompression = 0）：IPP Everywhere 强制要求实现它，
 * Windows 也只用它。遇到其它压缩值明确报错，不猜。
 *
 * **全程流式**：一页 600dpi 灰度就是 34.8MB，多页文件整读进内存必然 OOM，
 * 所以接口是「逐页回调」而不是「返回 List<Page>」。
 *
 * 编码与 USB 部分的公共骨架在 [RasterPrint]（URF 走同一段）。
 */
object PwgRaster {

    /** "RaS2"（CUPS_RASTER_SYNCv2）。 */
    private const val SYNC_WORD = 0x52615332

    /** cups_page_header2_t 的固定长度。字段顺序一变这个数就变，所以它同时是个校验。 */
    private const val HEADER_SIZE = 1796
    private const val PAGE_HEADER_SIZE = 4 + HEADER_SIZE

    // ── cups_page_header2_t 内的偏移（相对 header 起点，即同步字之后）──────────
    // 前 256 字节是 4 个 64 字节字符串（MediaClass/Color/Type/OutputType）。
    private const val O_HW_RES_X = 276
    private const val O_HW_RES_Y = 280
    private const val O_CUPS_WIDTH = 372
    private const val O_CUPS_HEIGHT = 376
    private const val O_BITS_PER_PIXEL = 388
    private const val O_BYTES_PER_LINE = 392
    private const val O_COLOR_SPACE = 400
    private const val O_COMPRESSION = 404
    private const val O_ROW_COUNT = 408
    /** cupsInteger[16] 起点；PWG 把 TotalPageCount 放在 [0]。 */
    private const val O_CUPS_INTEGER = 452

    // CUPS_CSPACE_*
    private const val CSPACE_W = 0        // DeviceGray，0=黑
    private const val CSPACE_RGB = 1      // DeviceRGB
    private const val CSPACE_K = 3        // DeviceK，0=无墨=白
    private const val CSPACE_SW = 18      // sGray
    private const val CSPACE_SRGB = 19

    /** 解出来的一页就是 [RasterPage]（与 URF / 后续格式共用同一种页面结构）。 */
    class BadRaster(message: String) : RasterFormatException(message)

    /**
     * 逐页解码，每页交给 [onPage] 后即可被回收 —— 峰值只有一页。
     *
     * 任何一处不自洽都抛 [BadRaster]，消息里带偏移和实际读到的值：
     * 打印链路的错误必须能直接指向原因，不能只是「解码失败」。
     */
    fun forEachPage(
        input: InputStream,
        onLog: (String) -> Unit = {},
        onPage: (index: Int, page: RasterPage) -> Unit,
    ) {
        val ins = if (input is BufferedInputStream) input else BufferedInputStream(input, 1 shl 16)
        val head = ByteArray(PAGE_HEADER_SIZE)
        var index = 0

        while (true) {
            val n = readFully(ins, head, PAGE_HEADER_SIZE)
            if (n == 0) break                       // 正常结束
            if (n < PAGE_HEADER_SIZE) {
                throw BadRaster("第 ${index + 1} 页页头只有 $n 字节，不足 $PAGE_HEADER_SIZE")
            }

            val sync = be32(head, 0)
            if (sync != SYNC_WORD) {
                // 不静默跳过：错位说明前面的长度计算已经错了，继续读只会越读越歪。
                throw BadRaster(
                    "第 ${index + 1} 页的同步字是 0x%08X，不是 RaS2（0x%08X）".format(sync, SYNC_WORD)
                )
            }

            val h = 4                               // 页头起点
            val width = be32(head, h + O_CUPS_WIDTH)
            val height = be32(head, h + O_CUPS_HEIGHT)
            val bpp = be32(head, h + O_BITS_PER_PIXEL)
            val bytesPerLine = be32(head, h + O_BYTES_PER_LINE)
            val colorSpace = be32(head, h + O_COLOR_SPACE)
            val compression = be32(head, h + O_COMPRESSION)
            val dpiX = be32(head, h + O_HW_RES_X)
            val dpiY = be32(head, h + O_HW_RES_Y)
            val rowCount = be32(head, h + O_ROW_COUNT)
            val totalPages = be32(head, h + O_CUPS_INTEGER)

            val pageNo = index + 1
            if (compression != 0) {
                throw BadRaster("第 $pageNo 页声明压缩方式 $compression，只支持无压缩（0）")
            }
            if (width <= 0 || height <= 0 || width > 20000 || height > 20000) {
                throw BadRaster("第 $pageNo 页尺寸不合理：${width}x$height")
            }
            val expectBpl = (width.toLong() * bpp + 7) / 8
            if (bytesPerLine.toLong() < expectBpl) {
                throw BadRaster(
                    "第 $pageNo 页 bytesPerLine=$bytesPerLine 小于最少需要的 $expectBpl（${width}px × ${bpp}bpp）"
                )
            }
            // PWG Raster 要求 RowCount 为 0；非 0 说明是 CUPS raster v1 的 RLE 行压缩，
            // 那是另一套编码，正确做法是明确拒绝而不是按自己的理解去猜。
            if (rowCount != 0) {
                throw BadRaster("第 $pageNo 页 RowCount=$rowCount，属于 CUPS raster v1 行压缩，未支持")
            }

            onLog(
                "第 $pageNo 页：%dx%d @%sx%sdpi %dbpp cs=%d 行宽=%d%s".format(
                    width, height, dpiX, dpiY, bpp, colorSpace, bytesPerLine,
                    if (totalPages > 0) "（共 $totalPages 页）" else "",
                )
            )

            val gray = readGray(ins, width, height, bpp, bytesPerLine, colorSpace, pageNo)
            index++
            onPage(index, RasterPage(width, height, dpiX, dpiY, gray))
        }

        if (index == 0) throw BadRaster("没有解析出任何页面")
    }

    /**
     * 逐行读像素并转灰度。0=黑，255=白，与 brcore 的约定一致。
     *
     * 支持的色彩空间之外（CMYK、CIELab、ICC…）明确拒绝：猜错一个分量顺序，
     * 打出来就是整页反相或全黑，比报错难查得多。
     */
    private fun readGray(
        ins: InputStream,
        width: Int,
        height: Int,
        bpp: Int,
        bytesPerLine: Int,
        colorSpace: Int,
        pageNo: Int,
    ): ByteArray {
        val supported = when {
            colorSpace == CSPACE_SW || colorSpace == CSPACE_W -> bpp == 8
            colorSpace == CSPACE_K -> bpp == 8
            colorSpace == CSPACE_SRGB || colorSpace == CSPACE_RGB -> bpp >= 24
            else -> bpp == 1
        }
        if (!supported) {
            throw BadRaster(
                "第 $pageNo 页色彩空间 $colorSpace / ${bpp}bpp 未支持" +
                    "（只处理 sGray 8bpp、sRGB 24bpp、1bpp）"
            )
        }

        val out = ByteArray(width * height)
        val row = ByteArray(bytesPerLine)

        for (y in 0 until height) {
            val got = readFully(ins, row, bytesPerLine)
            if (got < bytesPerLine) {
                throw BadRaster("第 $pageNo 页像素数据在第 ${y + 1} 行中断（$got/$bytesPerLine）")
            }
            val d = y * width

            when {
                // sGray / DeviceGray：单通道就是灰度，原样拷。
                colorSpace == CSPACE_SW || colorSpace == CSPACE_W ->
                    System.arraycopy(row, 0, out, d, width)

                // DeviceK：0 表示"不上墨"= 白，与灰度正好相反，必须取反。
                colorSpace == CSPACE_K -> for (x in 0 until width) {
                    out[d + x] = (255 - (row[x].toInt() and 0xFF)).toByte()
                }

                // sRGB / DeviceRGB：按 Rec.601 亮度加权。打印机只有一色墨，
                // 用亮度比取单通道更接近「人眼看到的深浅」。
                colorSpace == CSPACE_SRGB || colorSpace == CSPACE_RGB -> {
                    val step = bpp / 8
                    for (x in 0 until width) {
                        val p = x * step
                        val r = row[p].toInt() and 0xFF
                        val g = row[p + 1].toInt() and 0xFF
                        val b = row[p + 2].toInt() and 0xFF
                        out[d + x] = ((r * 77 + g * 151 + b * 28) shr 8).toByte()
                    }
                }

                // 1bpp：PWG 5102.4 §7.4.4 规定 bit=0 是黑、bit=1 是白。
                // 注意这与 CUPS 传统 PCL「1=上墨」相反，照搬会打出反相页。
                else -> for (x in 0 until width) {
                    val byte = row[x shr 3].toInt() and 0xFF
                    val bit = (byte shr (7 - (x and 7))) and 1
                    out[d + x] = if (bit == 0) 0 else 255.toByte()
                }
            }
        }
        return out
    }

    /**
     * 打印一个 PWG Raster 文件。
     *
     * 解码在这里，编码/双面/写 USB 的骨架在 [RasterPrint.execute] —— 与 URF
     * 共用同一段，保证两条路径的作业生命周期完全一致。
     */
    fun print(
        printer: UsbPrinter,
        file: File,
        options: com.cawzen.printserver.PrintOptions,
        onLog: (String) -> Unit = {},
    ): PdfPrint.Outcome = RasterPrint.execute("PWG Raster", file, options, printer, onLog) { emit, log ->
        file.inputStream().use { input ->
            forEachPage(input, onLog = log, onPage = { _, page -> emit(page) })
        }
    }

    /** 尽量读满 [len] 字节；返回实际读到的数量（流结束会小于 len，不抛异常）。 */
    private fun readFully(ins: InputStream, buf: ByteArray, len: Int): Int {
        var off = 0
        while (off < len) {
            val n = ins.read(buf, off, len - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    private fun be32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or
            ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or
            (b[at + 3].toInt() and 0xFF)
}
