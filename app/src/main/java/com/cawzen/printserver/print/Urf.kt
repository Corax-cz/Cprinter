package com.cawzen.printserver.print

import com.cawzen.printserver.usb.UsbPrinter
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

/**
 * URF / Apple Raster（`image/urf`）解码 —— AirPrint 的文档格式。
 *
 * ## 为什么 AirPrint 一定要它
 *
 * AirPrint 的模型是「客户端光栅化，打印机收位图」：iPhone 把 PDF 在本地渲染成 URF
 * 再发过来。只声明 `application/pdf` 的话 iOS 不会送 PDF（它也不保证会送），
 * 这台打印机在 iOS 里根本不会被选中。所以 URF 不是「可选优化」，是入口。
 *
 * ## 文件结构
 *
 * ```
 * 文件头 12 字节:  "UNIRAST\0" | u32be 页数
 * 每页 32 字节页头:                偏移
 *   bpp         u8                  0
 *   colorspace  u8                  1   0/4=灰 1/5=RGB 6=CMYK
 *   duplex      u8                  2   1=单面 2=长边 3=短边（只用于日志）
 *   quality     u8                  3
 *   mediaType / inputSlot / outputBin / copies  4..7
 *   finishings  u32                 8
 *   width       u32                 12
 *   height      u32                 16
 *   dpi         u32                 20
 *   保留        u32 ×2              24..31
 * 像素数据:  逐行
 *   重复字节  u8                    本行要重复 (值+1) 次
 *   然后是一串 PackBits 风格的游程，直到凑满 width 个像素：
 *     c <  0x80  → 下一个像素重复 (c+1) 次
 *     c == 0x80  → 本行提前结束，剩下的像素是白（0xFF）
 *     c >  0x80  → 接下来 (257-c) 个像素原样照抄
 * ```
 *
 * 每个「像素」占 `bpp/8` 字节。行内游程的总像素数必须正好等于 width。
 *
 * 注意这套编码与 PWG Raster 完全不同（PWG 是无压缩整行、页头 1796 字节），
 * 所以别想着复用 PWG 的解码器 —— 只能复用解码之后的部分（[RasterPrint]）。
 *
 * 0x80 的语义是三个独立实现（cups-filters 的 urftopdf、Ghostscript 的 urf
 * 解释器、go-unirast）一致的结论，不是猜的：它表示「本行到此为止，余白」。
 * 与之相对，0x00–0x7F 与 0x81–0xFF 的游程语义与标准 PackBits **正好反向**
 * （标准里 0x00–0x7F 是字面量），照抄 PackBits 的实现会得到一页乱码。
 *
 * **全程流式**：一页 600dpi 灰度 34.8MB，多页整读必 OOM，所以接口是逐页回调。
 */
object Urf {

    private const val FILE_HEADER = 12
    private const val PAGE_HEADER = 32

    /** 本行提前结束、余白。见文件头注释里三个实现的交叉验证。 */
    private const val END_OF_ROW = 0x80

    /**
     * 单页像素数上限。
     *
     * A4@600 是 4961×7016 ≈ 34.8M，A4@1200 是 139M。设 50M 就是「允许到 600dpi
     * 的整页，1200 明确拒绝」—— 拒绝比 OOM 好：OOM 会带走整个进程，
     * 而报错只会让这一个作业失败。
     */
    private const val MAX_PIXELS = 50_000_000L

    class BadUrf(message: String) : RasterFormatException(message)

    /**
     * 逐页解码，每页交给 [onPage] 后即可被回收。
     *
     * 任何一处不自洽都抛 [BadUrf]，消息里带页码/行号和实际读到的值 ——
     * 打印链路的错误必须能直接指向原因。
     */
    fun forEachPage(
        input: InputStream,
        onLog: (String) -> Unit = {},
        onPage: (index: Int, page: RasterPage) -> Unit,
    ) {
        val ins = if (input is BufferedInputStream) input else BufferedInputStream(input, 1 shl 16)

        val fh = ByteArray(FILE_HEADER)
        val n0 = readFully(ins, fh, FILE_HEADER)
        if (n0 < FILE_HEADER) throw BadUrf("文件头只有 $n0 字节，不足 $FILE_HEADER")
        if (String(fh, 0, 7, Charsets.ISO_8859_1) != "UNIRAST") {
            throw BadUrf("魔数不是 UNIRAST（实际：${hex(fh)}）")
        }
        val declaredPages = be32(fh, 8)
        onLog("UNIRAST 文件头：声明 $declaredPages 页")

        val head = ByteArray(PAGE_HEADER)
        var index = 0
        while (true) {
            val n = readFully(ins, head, PAGE_HEADER)
            if (n == 0) break
            if (n < PAGE_HEADER) {
                throw BadUrf("第 ${index + 1} 页页头只有 $n 字节，不足 $PAGE_HEADER")
            }

            val pageNo = index + 1
            val bpp = head[0].toInt() and 0xFF
            val cs = head[1].toInt() and 0xFF
            val duplexFlag = head[2].toInt() and 0xFF
            val quality = head[3].toInt() and 0xFF
            val copies = head[7].toInt() and 0xFF
            val width = be32(head, 12)
            val height = be32(head, 16)
            val dpi = be32(head, 20)

            if (width <= 0 || height <= 0 || width > 20000 || height > 20000) {
                throw BadUrf("第 $pageNo 页尺寸不合理：${width}x$height")
            }
            if (width.toLong() * height > MAX_PIXELS) {
                throw BadUrf(
                    "第 $pageNo 页 ${width}x$height 共 ${width.toLong() * height} 像素，" +
                        "超过单页上限 $MAX_PIXELS（多半是客户端送了 1200dpi）"
                )
            }
            val step = bpp / 8
            val supported = when {
                cs == CS_GRAY || cs == CS_DEVICE_GRAY -> bpp == 8
                cs == CS_SRGB || cs == CS_DEVICE_RGB -> bpp == 24 || bpp == 32
                else -> false
            }
            if (!supported) {
                throw BadUrf(
                    "第 $pageNo 页 colorspace=$cs / ${bpp}bpp 未支持" +
                        "（只处理灰 8bpp、RGB 24/32bpp；CMYK 6 明确不支持）"
                )
            }

            onLog(
                "第 $pageNo 页：%dx%d @%ddpi %dbpp cs=%d quality=%d duplex标志=%d%s%s".format(
                    width, height, dpi, bpp, cs, quality, duplexFlag,
                    if (copies > 1) " copies=$copies" else "",
                    if (declaredPages > 0) "（共 $declaredPages 页）" else "",
                )
            )
            if (dpi <= 0) onLog("  页头没给 dpi，按界面设置渲染")

            val gray = ByteArray(width * height)
            decodePixels(ins, width, height, step, cs, gray, pageNo, onLog)
            index++
            onPage(index, RasterPage(width, height, dpi, dpi, gray, duplexFlag.toDuplexHint()))
        }

        if (index == 0) throw BadUrf("没有解析出任何页面")
    }

    /**
     * 逐行解 PackBits 游程。
     *
     * 行的起点有一个「本行重复次数」字节：URF 用它压大面积相同的行
     * （比如整页全白），我们必须真的把这一行复制 N 遍，否则页高会短。
     */
    private fun decodePixels(
        ins: InputStream,
        width: Int,
        height: Int,
        step: Int,
        cs: Int,
        gray: ByteArray,
        pageNo: Int,
        onLog: (String) -> Unit,
    ) {
        val rowGray = ByteArray(width)
        // 单个游程最多 128 个像素（0x81 → 128 个字面量；0x7F → 128 个重复）。
        val runBuf = ByteArray(128 * step)
        val pixel = ByteArray(step)
        var clampedRows = 0
        var row = 0

        while (row < height) {
            val lineNo = row + 1
            val repByte = readByte(ins, "第 $pageNo 页第 $lineNo 行的重复计数")
            val lineRepeat = repByte + 1

            // 默认全白：0x80 提前结束行时，剩下的像素就保持这个值。
            rowGray.fill(255.toByte())

            var x = 0
            while (x < width) {
                val c = readByte(ins, "第 $pageNo 页第 $lineNo 行第 $x 个像素处")
                if (c == END_OF_ROW) break

                if (c < 0x80) {
                    // 重复游程：一个像素重复 (c+1) 次。
                    val n = c + 1
                    readPixel(ins, pixel, step, pageNo, lineNo)
                    val end = minOf(x + n, width)
                    if (end < x + n) clampedRows++
                    val g = grayOf(pixel, cs)
                    java.util.Arrays.fill(rowGray, x, end, g)
                    x = end
                } else {
                    // 字面量游程：(257-c) 个像素原样照抄。一次读完整个游程，
                    // 不管后面会不会被行宽截断 —— 少读一个字节整页就错位了。
                    val n = 257 - c
                    readPixelRun(ins, runBuf, n * step, pageNo, lineNo)
                    val usable = minOf(n, width - x)
                    if (usable < n) clampedRows++
                    if (step == 1) {
                        System.arraycopy(runBuf, 0, rowGray, x, usable)
                    } else {
                        for (i in 0 until usable) {
                            val p = i * step
                            rowGray[x + i] =
                                if (cs == CS_GRAY || cs == CS_DEVICE_GRAY) runBuf[p]
                                else lum(runBuf[p], runBuf[p + 1], runBuf[p + 2])
                        }
                    }
                    x += n
                }
            }

            val rows = minOf(lineRepeat, height - row)
            for (k in 0 until rows) {
                System.arraycopy(rowGray, 0, gray, (row + k) * width, width)
            }
            row += lineRepeat
        }

        if (clampedRows > 0) {
            onLog("  第 $pageNo 页有 $clampedRows 处游程越过行宽，已按行宽截断")
        }
    }

    private fun readPixel(ins: InputStream, buf: ByteArray, step: Int, pageNo: Int, lineNo: Int) {
        if (readFully(ins, buf, step) < step) {
            throw BadUrf("第 $pageNo 页第 $lineNo 行像素数据提前结束")
        }
    }

    private fun readPixelRun(ins: InputStream, buf: ByteArray, len: Int, pageNo: Int, lineNo: Int) {
        if (len <= 0) return
        if (readFully(ins, buf, len) < len) {
            throw BadUrf("第 $pageNo 页第 $lineNo 行字面量数据提前结束（还要 $len 字节）")
        }
    }

    // URF 的 colorspace 取值沿用 CUPS 的 cups_cspace_t。6（CMYK）明确不支持：
    // 猜错一个分量顺序就是整页反相或全黑，而单色打印机也用不到 CMYK。
    private const val CS_GRAY = 0        // W
    private const val CS_SRGB = 1
    private const val CS_DEVICE_GRAY = 4 // DeviceW
    private const val CS_DEVICE_RGB = 5  // DeviceRGB

    // 页头 duplex 字段。取值来自 CUPS 的 cups_duplex_t（与 PWG 一致）：
    // 0=未表态 / 1=单面 / 2=长边翻 / 3=短边翻。
    // 「短边」不是错误值 —— iOS 选「双面」但把装订边设成上边时就会送 3。
    private const val DUPLEX_SIMPLEX = 1
    private const val DUPLEX_LONG_EDGE = 2
    private const val DUPLEX_SHORT_EDGE = 3

    /**
     * 页头 duplex 字段 → 双面意图。0 与未知值返回 null（不表态），
     * 让上层回落到 IPP 的 `sides` 或界面设置，而不是硬当成单面。
     */
    private fun Int.toDuplexHint(): Boolean? = when (this) {
        DUPLEX_SIMPLEX -> false
        DUPLEX_LONG_EDGE, DUPLEX_SHORT_EDGE -> true
        else -> null
    }

    /** 单像素 → 灰度。0=黑 / 255=白，与 brcore 一致。 */
    private fun grayOf(p: ByteArray, cs: Int): Byte = when (cs) {
        CS_GRAY, CS_DEVICE_GRAY -> p[0]
        // Rec.601 亮度加权：打印机只有一色墨，用亮度比取单通道更接近人眼深浅。
        else -> lum(p[0], p[1], p[2])
    }

    private fun lum(r: Byte, g: Byte, b: Byte): Byte {
        val rr = r.toInt() and 0xFF
        val gg = g.toInt() and 0xFF
        val bb = b.toInt() and 0xFF
        return ((rr * 77 + gg * 151 + bb * 28) shr 8).toByte()
    }

    /**
     * 打印一个 URF 文件。解码在这里，编码/双面/USB 的骨架在 [RasterPrint.execute]。
     */
    fun print(
        printer: UsbPrinter,
        file: File,
        options: com.cawzen.printserver.PrintOptions,
        onLog: (String) -> Unit = {},
    ): PdfPrint.Outcome = RasterPrint.execute("URF", file, options, printer, onLog) { emit, log ->
        file.inputStream().use { input ->
            forEachPage(input, onLog = log, onPage = { _, page -> emit(page) })
        }
    }

    // ── 字节工具 ────────────────────────────────────────────────────────────

    private fun readByte(ins: InputStream, what: String): Int {
        val b = ins.read()
        if (b < 0) throw BadUrf("$what 之前文件结束")
        return b
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

    private fun hex(b: ByteArray): String =
        b.take(12).joinToString(" ") { "%02X".format(it) }
}
