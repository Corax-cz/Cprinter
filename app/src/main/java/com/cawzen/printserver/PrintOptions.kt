package com.cawzen.printserver

import android.content.Context

/**
 * 打印质量参数（界面可切换）。存 SharedPreferences 而不是内存变量，有两个原因：
 *
 * 1. 重启 app 后保持不变。600dpi 是用户目视选定的默认值，不该每次回到 300。
 * 2. IPP 服务与界面读**同一份**。否则会出现「界面上选了 600、局域网任务却按 300 出纸」
 *    这种只在纸面上才能发现的错位。
 */
data class PrintOptions(
    val dpi: Int = DEFAULT_DPI,
    val duplex: Boolean = false,
    val paperSize: String = PAPER_A4,
    /**
     * 份数。
     *
     * **不持久化，也不出现在界面上**。份数是作业级属性：由客户端在 IPP 的
     * `copies` 里逐作业指定（iOS 打印面板的「份数」），存进 SharedPreferences
     * 只会让上一份作业的份数污染下一份。
     *
     * 放在这里是因为编码层（`RasterPrint.execute`）只认 `PrintOptions`，
     * 单独加参数要穿过 PdfPrint / Urf / Pwg 三条调用链。
     */
    val copies: Int = 1,
) {

    /** 纸张像素尺寸，交给渲染层。 */
    fun paperPx(): Pair<Int, Int> =
        com.cawzen.printserver.print.Paper.sizePx(paperSize, dpi)

    /** 给日志用的一行摘要，格式与既有验收记录保持一致。 */
    fun describe(): String {
        val (w, h) = paperPx()
        return "$paperSize %dx%d @%ddpi duplex=%s copies=%d".format(w, h, dpi, duplex, copies)
    }

    fun withDpi(newDpi: Int) = copy(dpi = newDpi)
    fun withDuplex(newDuplex: Boolean) = copy(duplex = newDuplex)
    fun withCopies(n: Int) = copy(copies = n.coerceIn(MIN_COPIES, MAX_COPIES))

    companion object {
        /**
         * 可选 dpi。
         *
         * 底层（brcore::validDpi）还接受 1200，但没有开放：A4@1200 的灰度缓冲是
         * 9921×14032 ≈ 139MB，JVM 堆（256MB）装不下，条带渲染也救不了这个数组本身。
         * 600dpi 是这台打印机（HL-2240D 机芯）的物理上限，再高只是插值，没有信息量。
         */
        val DPI_CHOICES = listOf(300, 600)
        const val DEFAULT_DPI = 600

        const val PAPER_A4 = "A4"

        /**
         * 份数范围。
         *
         * 上限取 99 而不是 PCL `ESC&l#X` 的 999：多份是打印机自己重复整个作业，
         * 99 份已经是 198 页（双面），再高只是让误操作烧纸。
         */
        const val MIN_COPIES = 1
        const val MAX_COPIES = 99

        private const val PREF = "wprint_options"
        private const val KEY_DPI = "dpi"
        private const val KEY_DUPLEX = "duplex"
        private const val KEY_PAPER = "paper"
        private const val KEY_QUALITY_PICKED = "quality_picked"

        fun load(context: Context): PrintOptions {
            val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val dpi = sp.getInt(KEY_DPI, DEFAULT_DPI).let {
                if (it in DPI_CHOICES) it else DEFAULT_DPI
            }
            return PrintOptions(
                dpi = dpi,
                duplex = sp.getBoolean(KEY_DUPLEX, false),
                paperSize = sp.getString(KEY_PAPER, PAPER_A4) ?: PAPER_A4,
            )
        }

        fun save(context: Context, options: PrintOptions) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putInt(KEY_DPI, options.dpi)
                .putBoolean(KEY_DUPLEX, options.duplex)
                .putString(KEY_PAPER, options.paperSize)
                .apply()
        }

        /**
         * 用户是否手动选过画质。
         *
         * 用途：Stage 2 验收时默认值从 300 改成了 600（用户目视确认 600 更细腻）。
         * 但如果用户在旧版本里主动选过 300，升级后不该被悄悄改回 600 —— 尊重显式选择。
         * 只有从未选过的人才吃新的默认值。
         */
        fun hasExplicitQualityChoice(context: Context): Boolean =
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean(KEY_QUALITY_PICKED, false)

        /** 记录一次显式选择（同时写入 dpi）。 */
        fun saveDpi(context: Context, dpi: Int) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putInt(KEY_DPI, dpi)
                .putBoolean(KEY_QUALITY_PICKED, true)
                .apply()
        }
    }
}
