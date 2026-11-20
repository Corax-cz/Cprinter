package com.cawzen.printserver.print

import android.graphics.Bitmap

/**
 * Bitmap ↔ 灰度的转换。Stage 1 的测试图案和 Stage 2 的 PDF 渲染共用。
 *
 * 之所以要分块取行：A4@300 是 2480×3507，一次性 `getPixels` 到 IntArray 要多占 35MB，
 * 而灰度缓冲本身才 8.7MB。峰值内存是这条链路最容易崩的地方，逐块处理能把它压住。
 */
object BitmapUtils {

    /**
     * Bitmap → 灰度（0=黑，255=白，每像素 1 字节）。
     *
     * 用 77/150/29 的整数近似（BT.601 亮度公式）。对纯黑白图形结果就是 0 或 255；
     * 对彩色或灰阶内容，这是打印场景下正确的加权——人眼对绿色最敏感，直接取平均值
     * 会让黄色偏暗、蓝色偏亮。
     */
    fun toGray(bmp: Bitmap): ByteArray {
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

    /**
     * 为给定像素尺寸估算所需的 Bitmap 字节数，用于在渲染前做内存闸门。
     * RGB_565 每像素 2 字节，ARGB_8888 每像素 4 字节。
     */
    fun estimateBytes(width: Int, height: Int, config: Bitmap.Config): Long =
        width.toLong() * height.toLong() * when (config) {
            Bitmap.Config.ARGB_8888 -> 4L
            Bitmap.Config.RGB_565 -> 2L
            Bitmap.Config.ALPHA_8 -> 1L
            else -> 4L
        }
}
