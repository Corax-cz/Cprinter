package com.cawzen.printserver

import android.content.Context
import android.util.Log
import com.cawzen.printserver.print.NativeBr
import com.cawzen.printserver.usb.UsbPrinter
import java.io.File
import java.security.MessageDigest

/**
 * Stage 1：Native 渲染核心 + 直写打印。本项目的硬里程碑。
 *
 * 三层验证，缺一不可：
 *   ① 内部自洽：灰度入口与 1bpp 入口必须产出同一串字节（证明抖动+编码没有分叉）
 *   ② 外部一致：这些字节的 sha256 必须与 tools/hostref 的输出完全相同
 *      （hostref 与 .so 编译同一份 job.cc/line.cc，差异只可能来自桥接层）
 *   ③ 物理出纸：写到打印机，纸上的方块位置与比例正确
 */
object Stage1 {

    private const val TAG = "WPrint/Stage1"

    data class Outcome(val ok: Boolean, val report: String)

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun firstDiff(a: ByteArray, b: ByteArray): String {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            if (a[i] != b[i]) {
                val from = maxOf(0, i - 8)
                val to = minOf(n, i + 8)
                return "首个差异在 offset=$i：" +
                    "A=${a.copyOfRange(from, to).toHex()} B=${b.copyOfRange(from, to).toHex()}"
            }
        }
        return if (a.size != b.size) "前缀相同但长度不同：A=${a.size} B=${b.size}" else "完全一致"
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

    /**
     * ① 内部自洽 + ② 产出可与 hostref 比对的指纹。
     * 不碰硬件，可以随时跑。
     */
    fun byteIdentity(context: Context): Outcome {
        val sb = StringBuilder()
        val w = 200
        val h = 200
        val dpi = 600

        val gray = NativeBr.nativeTestPatternGray(w, h)
            ?: return Outcome(false, "测试图案生成失败")
        val packed = NativeBr.nativePack1bpp(gray, w, h, w)
            ?: return Outcome(false, "1bpp 打包失败")

        val viaGray = NativeBr.nativeRender(gray, w, h, w, dpi, false, "A4")
            ?: return Outcome(false, "nativeRender 返回 null（见 Logcat 的 IllegalArgumentException）")
        val viaPacked = NativeBr.nativeRender1bpp(packed, w, h, dpi, false, "A4")
            ?: return Outcome(false, "nativeRender1bpp 返回 null")

        val identical = viaGray.contentEquals(viaPacked)
        sb.append("=== Stage 1 字节自洽（%dx%d @%ddpi）===\n".format(w, h, dpi))
        sb.append("灰度入口：%d 字节  sha256=%s\n".format(viaGray.size, sha256(viaGray)))
        sb.append("1bpp 入口：%d 字节  sha256=%s\n".format(viaPacked.size, sha256(viaPacked)))
        sb.append("两条路径：").append(if (identical) "字节完全一致\n" else firstDiff(viaGray, viaPacked) + "\n")

        val packedSize = (w + 7) / 8 * h
        sb.append("打包位图：%d 字节（期望 %d）\n".format(packed.size, packedSize))
        sb.append("流头：" ).append(viaGray.copyOfRange(0, minOf(48, viaGray.size)).toHex()).append("\n")

        // 落盘，供 adb pull 后与 hostref 输出做二进制 diff
        runCatching {
            val dir = File(context.filesDir, "stage1").apply { mkdirs() }
            File(dir, "pattern_${w}x${h}_gray.bin").writeBytes(gray)
            File(dir, "pattern_${w}x${h}_packed.bin").writeBytes(packed)
            File(dir, "pattern_${w}x${h}_stream.bin").writeBytes(viaGray)
            sb.append("已保存：${dir.absolutePath}\n")
        }.onFailure { sb.append("落盘失败：${it.message}\n") }

        val ok = identical && viaGray.isNotEmpty()
        sb.append(if (ok) "\n① 内部自洽通过。\n" else "\n① 内部自洽失败。\n")
        Log.i(TAG, sb.toString())
        return Outcome(ok, sb.toString())
    }

    /**
     * ③ 物理出纸。A4 画布上画居中黑方块，尺寸与位置一眼可判。
     * 默认 300dpi：整页灰度缓冲 8.7MB，安全。600dpi 的整页路径留给 Stage 2
     * 的条带渲染，不在这里硬扛 34MB×2 的峰值。
     */
    @Throws(Exception::class)
    fun printTestPage(
        printer: UsbPrinter,
        context: Context,
        dpi: Int = 300,
        duplex: Boolean = false,
    ): Outcome {
        val sb = StringBuilder()
        // A4：210 x 297 mm（尺寸表集中在 Paper，避免与 PdfPrint 各写一份而漂移）
        val (w, h) = com.cawzen.printserver.print.Paper.sizePx("A4", dpi)
        sb.append("=== Stage 1 物理出纸（A4 %dx%d @%ddpi duplex=%s）===\n".format(w, h, dpi, duplex))

        val t0 = System.currentTimeMillis()
        val gray = NativeBr.nativeTestPatternGray(w, h)
            ?: return Outcome(false, sb.append("图案生成失败\n").toString())
        val t1 = System.currentTimeMillis()
        val stream = NativeBr.nativeRender(gray, w, h, w, dpi, duplex, "A4")
            ?: return Outcome(false, sb.append("编码失败\n").toString())
        val t2 = System.currentTimeMillis()

        sb.append("编码耗时 %d ms，输出 %d 字节，压缩比 %.1f%%\n".format(
            t2 - t1, stream.size, stream.size * 100.0 / (w * h / 8),
        ))
        sb.append("sha256=%s\n".format(sha256(stream)))

        val written = printer.writeAll(stream) { done, total ->
            if (done == total || done % (512 * 1024) == 0) {
                Log.i(TAG, "USB $done / $total")
            }
        }
        val t3 = System.currentTimeMillis()
        sb.append("USB 写入 %d 字节，耗时 %d ms\n".format(written, t3 - t2))
        sb.append("打印机状态：").append(printer.portStatusText()).append("\n")

        runCatching {
            val dir = File(context.filesDir, "stage1").apply { mkdirs() }
            File(dir, "print_a4_${dpi}_duplex$duplex.bin").writeBytes(stream)
        }

        val ok = written == stream.size
        sb.append(if (ok) "\n已发送完成，请检查出纸。\n" else "\n写入不完整。\n")
        Log.i(TAG, "print page: ${t3 - t0}ms total")
        return Outcome(ok, sb.toString())
    }

    // ── ④ 双面方向探针 ──────────────────────────────────────────────────────

    /**
     * 双面必须两页才有意义：一页的作业打印机根本不会启动双面器。
     *
     * 关键点：两页要放进**同一个** PCL job。拼接两个独立字节流会在第二页前重新发
     * 128 个 0 + PJL 头 + ESC E 复位，打印机把它当新作业，双面器不动。
     *
     * 纸面标记用于判断翻面方向：
     *   第 1 页 右上角大黑块、文字 "1 FRONT"
     *   第 2 页 左下角大黑块、文字 "2 BACK"
     * 短边装订（brlaser 发的 ESC&l2S）时，翻到背面看，"2 BACK" 应当是上下颠倒的，
     * 且黑块出现在左上。
     */
    @Throws(Exception::class)
    fun printDuplexProbe(
        printer: UsbPrinter,
        context: Context,
        dpi: Int = 300,
    ): Outcome {
        val sb = StringBuilder()
        val (w, h) = com.cawzen.printserver.print.Paper.sizePx("A4", dpi)
        sb.append("=== Stage 1-④ 双面探针（A4 %dx%d @%ddpi，2 页）===\n".format(w, h, dpi))

        val t0 = System.currentTimeMillis()
        val page1 = renderMarkedPage(w, h, "1", "FRONT / 正面", anchorTopRight = true)
        val page2 = renderMarkedPage(w, h, "2", "BACK / 反面", anchorTopRight = false)
        val t1 = System.currentTimeMillis()

        var handle = 0L
        val stream = try {
            handle = NativeBr.nativeJobBegin(dpi, true, 1, "A4")
            require(handle != 0L) { "nativeJobBegin 失败" }
            require(NativeBr.nativeJobAddPageGray(handle, page1, w, h, w)) { "第 1 页被拒" }
            require(NativeBr.nativeJobAddPageGray(handle, page2, w, h, w)) { "第 2 页被拒" }
            val count = NativeBr.nativeJobPageCount(handle)
            sb.append("作业内页数 = $count\n")
            NativeBr.nativeJobEnd(handle).also { handle = 0L }
                ?: error("nativeJobEnd 返回 null")
        } finally {
            if (handle != 0L) NativeBr.nativeJobAbort(handle)
        }
        val t2 = System.currentTimeMillis()

        sb.append("两页渲染 %d ms，编码 %d ms，输出 %d 字节\n".format(t1 - t0, t2 - t1, stream.size))
        sb.append("sha256=%s\n".format(sha256(stream)))
        // 双面指令必须在流里出现且只出现一次；出现的次数直接反映 job 结构对不对。
        val marker = byteArrayOf(0x1B, '&'.code.toByte(), 'l'.code.toByte(), '2'.code.toByte(),
            'S'.code.toByte())
        sb.append("ESC&l2S 出现次数 = ${countOccurrences(stream, marker)}\n")
        sb.append("PJL EOJ 出现次数 = ${countOccurrences(stream, "@PJL EOJ".toByteArray())}\n")

        val written = printer.writeAll(stream) { done, total ->
            if (done == total || done % (512 * 1024) == 0) Log.i(TAG, "USB $done / $total")
        }
        sb.append("USB 写入 %d 字节\n".format(written))

        runCatching {
            val dir = File(context.filesDir, "stage1").apply { mkdirs() }
            File(dir, "print_a4_${dpi}_duplex_probe.bin").writeBytes(stream)
        }

        val ok = written == stream.size
        sb.append(if (ok) "\n已发送，请检查：应该一张纸、正反两面。\n" else "\n写入不完整。\n")
        return Outcome(ok, sb.toString())
    }

    private fun countOccurrences(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return 0
        var count = 0
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            count++
        }
        return count
    }

    /**
     * 画一页方向标记。用 RGB_565 而非 ARGB_8888：A4@300 下前者 17MB、后者 35MB，
     * 而这里只有纯黑白，565 的色彩精度完全够用。
     */
    @Throws(Exception::class)
    private fun renderMarkedPage(
        w: Int,
        h: Int,
        pageNo: String,
        caption: String,
        anchorTopRight: Boolean,
    ): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.RGB_565)
        try {
            val c = android.graphics.Canvas(bmp)
            c.drawColor(android.graphics.Color.WHITE)
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            p.color = android.graphics.Color.BLACK

            // 边框：裁切与否一眼可见
            p.style = android.graphics.Paint.Style.STROKE
            p.strokeWidth = (w * 0.004f).coerceAtLeast(2f)
            c.drawRect(w * 0.03f, h * 0.03f, w * 0.97f, h * 0.97f, p)

            // 页码：足够大，隔着桌子都能读
            p.style = android.graphics.Paint.Style.FILL
            p.typeface = android.graphics.Typeface.DEFAULT_BOLD
            p.textSize = w * 0.26f
            c.drawText(pageNo, w * 0.08f, h * 0.22f, p)
            p.textSize = w * 0.055f
            c.drawText(caption, w * 0.08f, h * 0.29f, p)
            p.textSize = w * 0.028f
            c.drawText("wprint duplex probe  ${w}x$h @${
                (w * 25.4 / 210.0).toInt()
            }dpi", w * 0.08f, h * 0.94f, p)

            // 中心方块（与单面测试图案同尺寸，便于横向对照）
            val side = minOf(w, h) * 3 / 10
            c.drawRect(
                (w - side) / 2f, (h - side) / 2f,
                (w + side) / 2f, (h + side) / 2f, p,
            )

            // 方向锚：第 1 页右上角，第 2 页左下角
            val anchorSide = w * 0.08f
            val ax = if (anchorTopRight) w * 0.97f - anchorSide else w * 0.03f
            val ay = if (anchorTopRight) h * 0.03f else h * 0.97f - anchorSide
            c.drawRect(ax, ay, ax + anchorSide, ay + anchorSide, p)

            return bitmapToGray(bmp)
        } finally {
            bmp.recycle()
        }
    }

    /**
     * Bitmap → 灰度。分块取行，避免为 2480×3507 再分配一个 35MB 的 IntArray。
     * 走 77/150/29 的整数亮度公式，与常见 BT.601 一致，纯黑白下结果就是 0 或 255。
     */
    private fun bitmapToGray(bmp: android.graphics.Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val gray = ByteArray(w * h)
        val chunkRows = 256
        val rowBuf = IntArray(w * minOf(chunkRows, h))
        var y = 0
        while (y < h) {
            val rows = minOf(chunkRows, h - y)
            bmp.getPixels(rowBuf, 0, w, 0, y, w, rows)
            val base = y * w
            val n = w * rows
            for (i in 0 until n) {
                val color = rowBuf[i]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                gray[base + i] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
            }
            y += rows
        }
        return gray
    }
}
