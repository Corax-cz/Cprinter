package com.cawzen.printserver.print

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import androidx.core.graphics.PathParser
import com.cawzen.printserver.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 生成自检 PDF。
 *
 * 为什么不用现成 PDF：需要一个内容完全可预期的文件，打印出来能逐项对照——
 * 边距是否被裁、圆是否变形、字号是否正确、居中对不对。用别人的 PDF 只能看
 * 「像不像」，没法判断偏差出在渲染还是编码。
 *
 * **刻意做成「轻墨」版**：激光机打一页纯黑区块要磨掉不少粉，而只要对得上号，
 * 灰阶用几毫米宽的细条就够了。整页上墨面积压在 2% 以内，主要是字和细线 ——
 * 既省粉，又比大黑块更容易看出「细线有没有断、边缘有没有毛刺」。
 *
 * 走 `android.graphics.pdf.PdfDocument` 生成的是真正的矢量 PDF（写盘后再由
 * PdfRenderer 读回），与外部 PDF 走完全相同的解析与渲染路径，不是「自己画给
 * 自己看」的捷径。
 */
object TestPdf {

    /** A4 @ 72dpi（PDF 的用户单位是 pt）。 */
    private const val PAGE_W = 595
    private const val PAGE_H = 842

    fun generate(
        file: File,
        pages: Int = 1,
        options: com.cawzen.printserver.PrintOptions? = null,
        context: Context? = null,
    ): File {
        val doc = PdfDocument()
        try {
            for (i in 1..pages) {
                val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, i).create()
                val page = doc.startPage(info)
                drawPage(page.canvas, i, pages, options, context)
                doc.finishPage(page)
            }
            file.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        return file
    }

    /**
     * 右上角的 logo + 名字。
     *
     * **不能用 VectorDrawable.draw()**：它内部有一层 bitmap 缓存，带 tint 时会把整张
     * 图按 bounds 栅格化再贴上去。实测生成出来的 PDF 里是一个 30x30 像素的
     * `/Subtype /Image`，贴到 30pt 的地方等于自己把矢量变回糊图。
     *
     * 所以这里拿同一份 pathData（values/logo_path.xml，和 drawable/ic_logo.xml 同源，
     * 都由 tools/mklogo.py 生成）用 PathParser 还原成 Path 直接画 —— PDF 的 canvas
     * 是矢量设备，落进去就是真路径，300/600dpi 都是锐的。
     *
     * 名字用 PDF 字体而不是路径：这行字本来就是验字体的，转成路径反而看不出字形。
     */
    private fun drawLogo(c: android.graphics.Canvas, context: Context?) {
        val ctx = context ?: return
        val path = logoPath(ctx) ?: return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        val size = 46f                    // pt ≈ 16mm，比标题字高约 1.8 倍
        val right = (PAGE_W - 46).toFloat()
        val top = 46f
        c.save()
        c.translate(right - size, top)
        c.scale(size / 24f, size / 24f)   // pathData 的 viewport 是 24x24
        c.drawPath(path, paint)
        c.restore()

        // 名字压在 logo 底下。用**当前设置里的名字**（改名后这一行跟着变）——
        // 一页纸同时是"这台机器是谁"的凭证，打了三台网关时分不清是哪台。
        //
        // 排版要照顾长名字：「客厅的联想激光打印机」10 个字按 10pt 就是 100pt 宽，
        // 比 46pt 的 logo 宽一倍多。居中会在右侧留出很怪的悬空，所以：
        // 不超过 logo 宽 → 与 logo 同心；超过 → 右对齐到右边距，往左长。
        // 再长就缩字号，别让它顶到正文标题上。
        val label = com.cawzen.printserver.AppSettings.load(ctx).effectivePrinterName()
        val name = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 10f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val maxW = 160f
        val measured = name.measureText(label)
        if (measured > maxW) name.textSize = (10f * maxW / measured).coerceAtLeast(6f)
        val fitsUnderLogo = name.measureText(label) <= size
        if (!fitsUnderLogo) name.textAlign = Paint.Align.RIGHT
        c.drawText(
            label,
            if (fitsUnderLogo) right - size / 2 else right,
            top + size + 12f,
            name,
        )
    }

    /** 解析一次就够：一页里只画一个 logo，多页时也不用重复解析。 */
    private var cachedPath: Path? = null

    private fun logoPath(ctx: Context): Path? {
        cachedPath?.let { return it }
        val p = runCatching {
            PathParser.createPathFromPathData(ctx.getString(R.string.logo_path))
        }.getOrNull() ?: return null
        // 与矢量资源保持一致：13 个闭环里有镂空，nonZero 会填死。
        p.fillType = Path.FillType.EVEN_ODD
        cachedPath = p
        return p
    }

    private fun drawPage(
        c: android.graphics.Canvas,
        index: Int,
        total: Int,
        options: com.cawzen.printserver.PrintOptions? = null,
        context: Context? = null,
    ) {
        c.drawColor(Color.WHITE)

        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        // 细线统一 0.7pt：再细在 300dpi 下会因抖动断续，看不出是"线断了"还是"本来就这样"
        val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 0.7f
        }
        val mid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 1.4f
        }

        // ── 1. 边距框：距页边 20pt。打出来若被裁，一眼可见 ─────────────────
        c.drawRect(20f, 20f, (PAGE_W - 20).toFloat(), (PAGE_H - 20).toFloat(), hair)

        val left = 46f
        var y = 78f

        // ── 2. 标题 ─────────────────────────────────────────────────────────
        ink.textSize = 26f
        ink.isFakeBoldText = true
        c.drawText("Cprinter 测试页", left, y, ink)
        ink.isFakeBoldText = false

        // 右上角 logo：和标题同一水平线上，不占正文位置，也不额外上墨。
        drawLogo(c, context)

        y += 22f
        ink.textSize = 11f
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        c.drawText("第 $index / $total 页   $stamp", left, y, ink)

        y += 20f
        ink.textSize = 13f
        // 把本次实际使用的参数印在纸上：没有这行字，就没法判断「这张更细腻」
        // 是 600dpi 的功劳，还是心理作用 / 换了一叠纸。
        val summary = if (options != null) {
            "${options.dpi} dpi · ${if (options.duplex) "双面" else "单面"} · ${options.paperSize} · ${options.copies} 份"
        } else {
            "默认参数"
        }
        c.drawText(summary, left, y, ink)

        y += 14f
        c.drawLine(left, y, (PAGE_W - 40).toFloat(), y, hair)

        // ── 3. 几何：同心圆 + 十字 ──────────────────────────────────────────
        // 只画轮廓。若被拉伸成椭圆，说明 scale 计算或 DPI 换算错了；
        // 圆心到四条边的距离相等，也就同时验了左右边距对称。
        val cx = PAGE_W / 2f
        val cy = y + 118f
        for (r in intArrayOf(38, 62, 86)) {
            c.drawCircle(cx, cy, r.toFloat(), hair)
        }
        c.drawLine(cx - 104f, cy, cx + 104f, cy, hair)
        c.drawLine(cx, cy - 104f, cx, cy + 104f, hair)

        y = cy + 126f

        // ── 4. 细线组：1px 能不能连续 ──────────────────────────────────────
        // 三条不同角度。斜线最容易看出抖动算法导致的花斑。
        ink.textSize = 9f
        c.drawText("线条连续性", left, y, ink)
        y += 10f
        c.drawLine(left, y, (left + 150), y, hair)
        c.drawLine(left, y + 8f, (left + 150), y + 8f, mid)
        c.drawLine(left, y + 20f, (left + 150), y + 40f, hair)
        c.drawLine((left + 170), y, (left + 240), y + 40f, hair)

        y += 62f

        // ── 5. 灰阶：细条，不是色块 ────────────────────────────────────────
        // 12 档 4pt 高的小条，足够看出半色调有没有层次，上墨面积不到原来的 1/10。
        ink.textSize = 9f
        c.drawText("灰阶 白 → 黑（应连续过渡，不出现断带）", left, y, ink)
        y += 7f
        val steps = 12
        val barW = 34f
        val barH = 5f
        for (i in 0 until steps) {
            val v = 255 - i * 255 / (steps - 1)
            ink.color = Color.rgb(v, v, v)
            c.drawRect(left + i * (barW + 2f), y, left + i * (barW + 2f) + barW, y + barH, ink)
        }
        ink.color = Color.BLACK
        y += barH + 22f

        // ── 6. 字号梯度：小字糊不糊是 300dpi 够不够用的直接判据 ─────────────
        ink.textSize = 9f
        c.drawText("字号梯度（最小一行若糊成一团，请把画质调到 600dpi）", left, y, ink)
        y += 14f
        val sizes = intArrayOf(6, 8, 10, 12, 16, 22)
        for (s in sizes) {
            ink.textSize = s.toFloat()
            c.drawText(
                "${s}pt  Cprinter 0123456789 ABCdef 打印机测试",
                left, y, ink,
            )
            y += s + 8f
        }

        y += 6f

        // ── 7. 反白小样：小面积黑底，验"白字不被糊死" ──────────────────────
        // 只用一枚小标签。反白字在激光机上很吃粉，够验证即可 —— 曾经这块是
        // 96x16pt 的实心条，用户第一反应是「好浪费墨」，这个反馈是对的。
        ink.textSize = 9f
        c.drawText("反白小样", left, y, ink)
        y += 7f
        val badgeW = 62f
        val badgeH = 11f
        c.drawRect(left, y, left + badgeW, y + badgeH, ink)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 7f
        }
        c.drawText("WHITE ON BLACK", left + 4f, y + 8f, white)

        // ── 8. 页脚 ────────────────────────────────────────────────────────
        ink.textSize = 8f
        c.drawText(
            "A4 210x297mm · 本页为轻墨测试版，上墨面积 < 2%",
            left, (PAGE_H - 34).toFloat(), ink,
        )
    }
}
