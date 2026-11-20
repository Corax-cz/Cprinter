package com.cawzen.printserver.print

/**
 * 纸张尺寸表。只放真正会用到的那几种，其它一律回落 A4（与 brcore::normalizePaperSize 行为一致）。
 *
 * 为什么单独抽出来：Stage 2 的 PdfPrint 和 Stage 1 的测试页都要把 mm 换成像素，
 * 两边各写一份 `210.0 / 25.4` 迟早会漂移——LETTER 的 215.9 就是一个容易漏掉的差异。
 */
object Paper {

    /** 返回 (宽mm, 高mm)。未知名称回落 A4。 */
    fun sizeMm(name: String): Pair<Double, Double> = when (name.uppercase()) {
        "LETTER" -> 215.9 to 279.4
        "LEGAL" -> 215.9 to 355.6
        "A5" -> 148.0 to 210.0
        else -> 210.0 to 297.0
    }

    /** 纸张在给定 dpi 下的像素尺寸。 */
    fun sizePx(name: String, dpi: Int): Pair<Int, Int> {
        val (wmm, hmm) = sizeMm(name)
        return mmToPx(wmm, dpi) to mmToPx(hmm, dpi)
    }

    /**
     * 用四舍五入而不是截断。
     *
     * A4 高 297mm 在 300dpi 下是 3507.87 像素，截断会给 3507、四舍五入给 3508。
     * 这个差看着无关紧要，但 PWG Raster 客户端算出来是 3508（它们也四舍五入），
     * 两边差 1 像素就会让整页走进「尺寸不符 → 重采样」的分支，白白丢掉质量。
     */
    fun mmToPx(mm: Double, dpi: Int): Int = Math.round(mm / 25.4 * dpi).toInt()
}
