package com.cawzen.printserver.print

import android.util.Log
import com.cawzen.printserver.usb.UsbPrinter
import java.io.File

/**
 * PCL 直通（不过渲染）。
 *
 * 为什么需要它：Windows 上的「Lenovo LJ2600D」厂商驱动渲染出来的是 PCL5，
 * 不是 PDF。想让它走我们的服务器，只有两条路——在手机上重新解释 PCL（等于重写
 * 一个 PCL 光栅化器），或者原样把字节交给打印机。后者才是对的：这台机器的
 * 固件本来就懂 PCL，Windows 那边渲染的质量也只会更好（它有完整字体和图形栈）。
 *
 * 这条路径的语义是「我是一个透明的 IPP→USB 桥」，与老式 CUPS raw 队列完全一致。
 *
 * 它也是**唯一能跨品牌**的路径：不渲染就不需要理解页面语言，所以 PostScript 打印机
 * （HP / 京瓷 / 理光）、PCL6 打印机（佳能 / 奔图）都能用，只要客户端自己有驱动。
 * 前提是机芯真的懂那个格式 —— 兄弟 HL-2240D 只有自家 PCL 解释器，没有 PS。
 */
object RawPrint {

    private const val TAG = "WPrint/Raw"

    data class Result(val ok: Boolean, val report: String)

    fun print(
        printer: UsbPrinter,
        file: File,
        kindLabel: String = "PCL 直通",
        onLog: (String) -> Unit = {},
    ): Result {
        val sb = StringBuilder()
        fun line(s: String) {
            sb.append(s).append("\n")
            Log.i(TAG, s)
            onLog(s)
        }

        val bytes = file.readBytes()
        line("$kindLabel：${file.name}（${bytes.size} 字节，未做任何转换）")
        if (bytes.isEmpty()) return Result(false, sb.append("文件为空\n").toString())

        val t0 = System.currentTimeMillis()
        val written = try {
            printer.writeAll(bytes) { done, total ->
                if (done == total || done % (512 * 1024) == 0) Log.i(TAG, "USB $done / $total")
            }
        } catch (e: Exception) {
            return Result(false, sb.append("USB 写入失败：${e.message}\n").toString())
        }
        val t1 = System.currentTimeMillis()
        line("USB 写入 $written 字节，耗时 ${t1 - t0} ms")
        line("打印机状态：${printer.portStatusText()}")

        val ok = written == bytes.size
        line(if (ok) "已直通发送，请检查出纸。" else "写入不完整。")
        return Result(ok, sb.toString())
    }
}

/**
 * 文档类型嗅探。**看字节，不看客户端声明的 document-format** ——
 * 声明 application/pdf 却送 PostScript、声明 octet-stream 却送 PCL 的情况都真实存在，
 * 而把 PCL 丢进 PdfRenderer 会得到 "文档损坏" 这种毫无信息量的报错。
 */
object DocumentSniffer {

    enum class Kind { PDF, PCL, PWG_RASTER, URF, POSTSCRIPT, UNKNOWN }

    fun sniff(bytes: ByteArray): Kind {
        if (bytes.size < 4) return Kind.UNKNOWN
        val head = String(bytes, 0, minOf(64, bytes.size), Charsets.ISO_8859_1)

        if (head.startsWith("%PDF")) return Kind.PDF
        if (head.startsWith("%!PS")) return Kind.POSTSCRIPT
        // URF（Apple Raster）：魔数 "UNIRAST\0"。**必须在 PCL 探测之前判断** ——
        // 这只是顺序习惯，两种魔数不会互相误判，但先判更贵的/更具体的更清楚。
        if (head.startsWith("UNIRAST")) return Kind.URF
        // PWG Raster：文件头 "RaS2"（或老版本 "PwgR"）
        if (head.startsWith("RaS2") || head.startsWith("PwgR")) return Kind.PWG_RASTER

        // PCL：要么以 UEL(ESC%-12345X) 开头，要么直接 PJL，要么直接 ESC E 复位。
        // 前 1KB 内出现 @PJL 也算 —— 有些驱动会先写几行注释再发 PJL。
        val probe = String(
            bytes, 0, minOf(1024, bytes.size), Charsets.ISO_8859_1,
        )
        if (probe.startsWith("\u001b%-12345X") || probe.startsWith("\u001bE") ||
            probe.contains("@PJL") || probe.contains("\u001b&l")
        ) {
            return Kind.PCL
        }
        return Kind.UNKNOWN
    }

    fun describe(kind: Kind): String = when (kind) {
        Kind.PDF -> "PDF"
        Kind.PCL -> "PCL/PJL"
        Kind.PWG_RASTER -> "PWG Raster"
        Kind.URF -> "URF (Apple Raster)"
        Kind.POSTSCRIPT -> "PostScript"
        Kind.UNKNOWN -> "未知"
    }
}
