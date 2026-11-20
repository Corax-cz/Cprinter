package com.cawzen.printserver.print

/**
 * JNI 入口。签名与项目规格书一致（nativeRender 不得更改）。
 *
 * 额外的 nativeRender1bpp / nativePack1bpp / nativeTestPatternGray 只为回归比对，
 * 生产路径只走 nativeRender。
 */
object NativeBr {

    init {
        System.loadLibrary("wprint")
    }

    /**
     * 灰度像素 → Brother/PCL 字节流。
     *
     * @param pixels 灰度像素，每像素 1 字节，0=黑 255=白
     * @param bytesPerLine 灰度行的字节跨度（= stride，通常等于 width）
     * @param dpi 300 / 600 / 1200
     * @return PCL 字节流；参数非法时抛 IllegalArgumentException（JNI 侧返回 null）
     */
    external fun nativeRender(
        pixels: ByteArray,
        width: Int,
        height: Int,
        bytesPerLine: Int,
        dpi: Int,
        duplex: Boolean,
        paperSize: String,
    ): ByteArray?

    /** 已是 1bpp 打包位图（bit=1 为墨）时直接编码，跳过抖动。 */
    external fun nativeRender1bpp(
        packed: ByteArray,
        width: Int,
        height: Int,
        dpi: Int,
        duplex: Boolean,
        paperSize: String,
    ): ByteArray?

    /** 灰度 → 1bpp（8x8 Bayer），便于落盘成可复现的 testdata。 */
    external fun nativePack1bpp(gray: ByteArray, width: Int, height: Int, stride: Int): ByteArray?

    /** Stage 1 测试图案：白底 + 居中黑方块。由 C++ 生成，与 hostref 输入逐字节相同。 */
    external fun nativeTestPatternGray(width: Int, height: Int): ByteArray?

    // ── 多页会话（双面/多页需要）──────────────────────────────────────────
    // 为什么不是一个「N 页一次性传」的函数：A4@600 一页灰度就 34MB，整本 PDF 不可能
    // 同时驻留内存；而双面又必须把多页塞进同一个 PCL job，不能拼接独立字节流。
    // 所以做成句柄式流式：每页 encode 完即可释放。

    /**
     * 开启作业。返回 0 表示失败（dpi 非法或内存不足）。
     *
     * @param copies 份数，交给 PCL 的 `ESC&l#X` 让打印机自己重复整个作业。
     *   不要改成一页编码 N 遍：600dpi 双页作业 288KB 会放大 N 倍，
     *   而 USB 只有约 1.5MB/s。
     */
    external fun nativeJobBegin(dpi: Int, duplex: Boolean, copies: Int, paperSize: String): Long

    /** 追加一页灰度。返回 false 表示该页被拒（尺寸与前页不一致等）。 */
    external fun nativeJobAddPageGray(
        handle: Long,
        gray: ByteArray,
        width: Int,
        height: Int,
        stride: Int,
    ): Boolean

    /** 已加入的页数；句柄无效返回 -1。 */
    external fun nativeJobPageCount(handle: Long): Int

    /** 结束作业并取回完整 PCL 字节流。句柄随即失效。 */
    external fun nativeJobEnd(handle: Long): ByteArray?

    /** 异常路径丢弃作业。可重复调用。 */
    external fun nativeJobAbort(handle: Long)
}
