package com.cawzen.printserver.ui

import kotlin.math.absoluteValue

/**
 * 极简 QR Code 生成器（byte 模式，纠错级别 M，版本 1~4）。
 *
 * 只为本 App 的「展示 IPP 地址」服务。算法严格对齐 ISO/IEC 18004 与主流实现
 * （python-qrcode 的 map_data / best_mask_pattern / lost_point），关键点：
 *   - 三态矩阵（null = 未填充）：数据放置只填 null 位，天然跳过功能图案。
 *   - 掩码在放置数据时**内联**应用（不是先放完再掩码），与标准一致。
 *   - 掩码选择用 N1/N2/N3/N4 四条惩罚规则。
 *
 * 版本上限 4（byte 模式 + M 级最多 61 字节），足够容纳一条 IPP URL。
 */
object QrCode {

    /** 生成 size×size 的布尔矩阵（true = 黑）。 */
    fun encode(text: String): BooleanArray2d {
        val data = text.toByteArray(Charsets.ISO_8859_1)
        val version = when {
            data.size + 3 <= 16 -> 1
            data.size + 3 <= 28 -> 2
            data.size + 3 <= 44 -> 3
            data.size + 3 <= 64 -> 4
            else -> throw IllegalArgumentException("内容过长（${data.size} 字节），仅支持 <=61 字节")
        }
        return encodeVersion(version, data)
    }

    private fun encodeVersion(version: Int, data: ByteArray): BooleanArray2d {
        val size = version * 4 + 17
        val ec = ecBlocks(version)

        // 1. 数据位流 → 数据码字
        val dataCodewords = buildDataCodewords(ec.dataCodewords, data)
        // 2. 纠错码字
        val ecCodewords = reedSolomon(dataCodewords, ec.ecPerBlock, ec.blocks)
        // 3. 交错
        val allCodewords = interleave(dataCodewords, ecCodewords, ec.blocks, ec.dataPerBlock, ec.ecPerBlock)

        // 4. 功能图案（三态矩阵）
        val matrix = TripleMatrix(size)
        drawFunctionPatterns(matrix, version)

        // 5. 选掩码（标准 lost_point）
        val bestMask = chooseBestMask(matrix, allCodewords)
        // 6. 用最佳掩码填数据（含格式信息）
        mapData(matrix, allCodewords, bestMask)
        drawFormatInfo(matrix, bestMask)
        return matrix.toBool()
    }

    // ── 三态矩阵 ────────────────────────────────────────────────────────────

    private class TripleMatrix(val size: Int) {
        // null = 未填充
        val cells = arrayOfNulls<Boolean?>(size * size)
        operator fun get(x: Int, y: Int): Boolean? = cells[y * size + x]
        operator fun set(x: Int, y: Int, v: Boolean?) { cells[y * size + x] = v }
        fun copy(): TripleMatrix {
            val c = TripleMatrix(size)
            cells.copyInto(c.cells)
            return c
        }
        fun toBool(): BooleanArray2d {
            val b = BooleanArray2d(size)
            for (i in cells.indices) b.cells[i] = cells[i] == true
            return b
        }
    }

    class BooleanArray2d(val size: Int) {
        val cells = BooleanArray(size * size)
        operator fun get(x: Int, y: Int) = cells[y * size + x]
        operator fun set(x: Int, y: Int, v: Boolean) { cells[y * size + x] = v }
    }

    // ── 数据位流 ────────────────────────────────────────────────────────────

    private fun buildDataCodewords(dataCount: Int, data: ByteArray): ByteArray {
        val bits = mutableListOf<Boolean>()
        fun put(v: Int, c: Int) { for (i in c - 1 downTo 0) bits.add((v shr i) and 1 == 1) }
        put(0b0100, 4) // byte 模式
        put(data.size, 8) // 字符计数（版本 1~9 用 8 位）
        for (b in data) put(b.toInt() and 0xFF, 8)
        val cap = dataCount * 8
        put(0, minOf(4, cap - bits.size))
        while (bits.size % 8 != 0) bits.add(false)
        var pad = 0xEC
        while (bits.size < cap) { put(pad, 8); pad = if (pad == 0xEC) 0x11 else 0xEC }
        val out = ByteArray(dataCount)
        for (i in 0 until dataCount) {
            var v = 0
            for (j in 0 until 8) v = (v shl 1) or (if (bits[i * 8 + j]) 1 else 0)
            out[i] = v.toByte()
        }
        return out
    }

    // ── Reed-Solomon（GF(256)）──────────────────────────────────────────────

    private val gfExp = IntArray(512)
    private val gfLog = IntArray(256)
    init {
        var x = 1
        for (i in 0 until 255) { gfExp[i] = x; gfLog[x] = i; x = x shl 1; if (x and 0x100 != 0) x = x xor 0x11D }
        for (i in 255 until 512) gfExp[i] = gfExp[i - 255]
    }

    private fun gfMul(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else gfExp[gfLog[a] + gfLog[b]]

    private fun rsGenerator(degree: Int): IntArray {
        // 生成多项式 g(x) = ∏(x + α^i)，i = 0..degree-1。
        // 返回「去掉最高次项（x^degree 系数恒为 1）后」的系数，高次在前：
        //   gen[0] = x^(degree-1) 系数, ..., gen[degree-1] = x^0 系数（常数项）。
        // 这样寄存器法里 gen[j] 与 res[j]（同为 x^(degree-1-j) 系数）一一对齐。
        var product = intArrayOf(1)
        for (i in 0 until degree) product = polyMul(product, intArrayOf(1, gfExp[i]))
        // product 是完整多项式（degree+1 项，高次在前），去掉首项（x^degree 系数 1）
        val g = IntArray(degree)
        for (i in 0 until degree) g[i] = product[i + 1]
        return g
    }

    private fun polyMul(a: IntArray, b: IntArray): IntArray {
        val r = IntArray(a.size + b.size - 1)
        for (i in a.indices) for (j in b.indices) r[i + j] = r[i + j] xor gfMul(a[i], b[j])
        return r
    }

    private fun reedSolomon(data: ByteArray, ecCount: Int, blocks: Int): ByteArray {
        val dataPer = data.size / blocks
        val gen = rsGenerator(ecCount)
        val out = ByteArray(ecCount * blocks)
        for (b in 0 until blocks) {
            val res = IntArray(ecCount)
            for (i in 0 until dataPer) {
                val factor = (data[b * dataPer + i].toInt() and 0xFF) xor res[0]
                System.arraycopy(res, 1, res, 0, ecCount - 1)
                res[ecCount - 1] = 0
                for (j in 0 until ecCount) res[j] = res[j] xor gfMul(gen[j], factor)
            }
            for (j in 0 until ecCount) out[b * ecCount + j] = (res[j] and 0xFF).toByte()
        }
        return out
    }

    private fun interleave(data: ByteArray, ec: ByteArray, blocks: Int, dataPer: Int, ecPer: Int): ByteArray {
        val out = ByteArray(data.size + ec.size)
        var p = 0
        for (i in 0 until dataPer) for (b in 0 until blocks) out[p++] = data[b * dataPer + i]
        for (i in 0 until ecPer) for (b in 0 until blocks) out[p++] = ec[b * ecPer + i]
        return out
    }

    // ── 纠错块配置（版本 1~4，级别 M）───────────────────────────────────────

    private data class EcInfo(val dataCodewords: Int, val blocks: Int, val dataPerBlock: Int, val ecPerBlock: Int)

    private fun ecBlocks(version: Int): EcInfo = when (version) {
        1 -> EcInfo(16, 1, 16, 10)
        2 -> EcInfo(28, 1, 28, 16)
        3 -> EcInfo(44, 1, 44, 26)
        4 -> EcInfo(64, 2, 32, 18)
        else -> throw IllegalArgumentException("不支持版本 $version")
    }

    // ── 功能图案 ────────────────────────────────────────────────────────────

    private fun drawFunctionPatterns(m: TripleMatrix, version: Int) {
        val size = m.size
        drawFinder(m, 0, 0)
        drawFinder(m, size - 7, 0)
        drawFinder(m, 0, size - 7)
        for (i in 8 until size - 8) {
            m[i, 6] = (i % 2 == 0)
            m[6, i] = (i % 2 == 0)
        }
        // 定位（alignment）图案：version>=2 时右下角有一个
        if (version >= 2) {
            val cx = size - 7
            val cy = size - 7
            for (dy in -2..2) for (dx in -2..2) {
                m[cx + dx, cy + dy] = (dx == -2 || dx == 2 || dy == -2 || dy == 2 || (dx == 0 && dy == 0))
            }
        }
        // 格式信息区占位（先填 false，避免 map_data 误入）；实际值后面 drawFormatInfo 覆盖
        // vertical：x=8 列
        for (i in 0 until 15) {
            when {
                i < 6 -> m[8, i] = false
                i < 8 -> m[8, i + 1] = false
                else -> m[8, size - 15 + i] = false
            }
        }
        // horizontal：y=8 行
        for (i in 0 until 15) {
            when {
                i < 8 -> m[size - i - 1, 8] = false
                i < 9 -> m[7, 8] = false
                else -> m[15 - i - 1, 8] = false
            }
        }
        // 暗模块
        m[8, size - 8] = true
    }

    private fun drawFinder(m: TripleMatrix, x: Int, y: Int) {
        // finder 是 7×7（0..6），外圈黑 + 中心 3×3 黑；分隔区（-1 和 7）是白。
        for (dy in -1..7) for (dx in -1..7) {
            val nx = x + dx; val ny = y + dy
            if (nx < 0 || ny < 0 || nx >= m.size || ny >= m.size) continue
            val inFinder = dx in 0..6 && dy in 0..6
            val ring = dx == 0 || dx == 6 || dy == 0 || dy == 6
            val core = dx in 2..4 && dy in 2..4
            m[nx, ny] = inFinder && (ring || core)
        }
    }

    // ── 掩码函数 ────────────────────────────────────────────────────────────

    private fun maskBit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (x + y) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (x + y) % 3 == 0
        4 -> (x / 3 + y / 2) % 2 == 0
        5 -> (x * y) % 2 + (x * y) % 3 == 0
        6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
        7 -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
        else -> false
    }

    // ── 数据放置（对齐 python-qrcode map_data）──────────────────────────────

    private fun mapData(m: TripleMatrix, codewords: ByteArray, mask: Int) {
        val size = m.size
        val dataLen = codewords.size
        var inc = -1
        var row = size - 1
        var bitIndex = 7
        var byteIndex = 0

        var col = size - 1
        while (col > 0) {
            val colStart = if (col <= 6) col - 1 else col
            val colRange = intArrayOf(colStart, colStart - 1)
            while (true) {
                for (c in colRange) {
                    if (c < 0) continue
                    if (m[c, row] == null) {
                        var dark = false
                        if (byteIndex < dataLen) {
                            dark = ((codewords[byteIndex].toInt() shr bitIndex) and 1) == 1
                        }
                        if (maskBit(mask, c, row)) dark = !dark
                        m[c, row] = dark
                        bitIndex--
                        if (bitIndex == -1) { byteIndex++; bitIndex = 7 }
                    }
                }
                row += inc
                if (row < 0 || row >= size) {
                    row -= inc
                    inc = -inc
                    break
                }
            }
            col -= 2
        }
    }

    // ── 掩码选择（对齐 python-qrcode lost_point）────────────────────────────

    private fun chooseBestMask(base: TripleMatrix, codewords: ByteArray): Int {
        var bestMask = 0
        var bestScore = Int.MAX_VALUE
        for (mask in 0 until 8) {
            val m = base.copy()
            mapData(m, codewords, mask)
            val score = lostPoint(m)
            if (score < bestScore) { bestScore = score; bestMask = mask }
        }
        return bestMask
    }

    private fun lostPoint(m: TripleMatrix): Int {
        val size = m.size
        var score = 0
        // 转 bool（null 当 false）
        fun v(x: Int, y: Int) = m[x, y] == true

        // N1: 行/列连续同色 >=5
        for (y in 0 until size) {
            var run = 1
            var prev = v(0, y)
            for (x in 1 until size) {
                val cur = v(x, y)
                if (cur == prev) run++ else { if (run >= 5) score += 3 + run - 5; run = 1; prev = cur }
            }
            if (run >= 5) score += 3 + run - 5
        }
        for (x in 0 until size) {
            var run = 1
            var prev = v(x, 0)
            for (y in 1 until size) {
                val cur = v(x, y)
                if (cur == prev) run++ else { if (run >= 5) score += 3 + run - 5; run = 1; prev = cur }
            }
            if (run >= 5) score += 3 + run - 5
        }

        // N2: 2x2 块
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val a = v(x, y)
            if (a == v(x + 1, y) && a == v(x, y + 1) && a == v(x + 1, y + 1)) score += 3
        }

        // N3: 1:1:3:1:1 图案，前面或后面紧跟 4 个亮模块
        // 行方向
        for (y in 0 until size) {
            var x = 0
            while (x <= size - 11) {
                val hit =
                    !v(x + 1, y) && v(x + 4, y) && !v(x + 5, y) && v(x + 6, y) &&
                    !v(x + 9, y) &&
                    ((v(x, y) && v(x + 2, y) && v(x + 3, y) && !v(x + 7, y) &&
                        !v(x + 8, y) && !v(x + 10, y)) ||
                        (!v(x, y) && !v(x + 2, y) && !v(x + 3, y) && v(x + 7, y) &&
                            v(x + 8, y) && v(x + 10, y)))
                if (hit) score += 40
                if (v(x + 10, y)) x += 2 else x += 1
            }
        }
        // 列方向
        for (x in 0 until size) {
            var y = 0
            while (y <= size - 11) {
                val hit =
                    !v(x, y + 1) && v(x, y + 4) && !v(x, y + 5) && v(x, y + 6) &&
                    !v(x, y + 9) &&
                    ((v(x, y) && v(x, y + 2) && v(x, y + 3) && !v(x, y + 7) &&
                        !v(x, y + 8) && !v(x, y + 10)) ||
                        (!v(x, y) && !v(x, y + 2) && !v(x, y + 3) && v(x, y + 7) &&
                            v(x, y + 8) && v(x, y + 10)))
                if (hit) score += 40
                if (v(x, y + 10)) y += 2 else y += 1
            }
        }

        // N4: 黑白比例（每偏离 50% 5 个百分点加 10 分）
        var dark = 0
        for (y in 0 until size) for (x in 0 until size) if (v(x, y)) dark++
        val total = size * size
        val rating = (dark * 100 / total - 50).absoluteValue / 5
        score += rating * 10
        return score
    }

    // ── 格式信息 ────────────────────────────────────────────────────────────

    private fun drawFormatInfo(m: TripleMatrix, mask: Int) {
        val size = m.size
        // M 级别 = 0b00
        val data = (0b00 shl 3) or mask
        var bits = data shl 10
        val g = 0x537
        var rem = bits
        for (i in 14 downTo 10) if (rem and (1 shl i) != 0) rem = rem xor (g shl (i - 10))
        bits = (data shl 10) or rem
        bits = bits xor 0x5412

        // vertical：x=8 这一列
        for (i in 0 until 15) {
            val mod = (bits shr i) and 1 == 1
            when {
                i < 6 -> m[8, i] = mod                       // (8,0)~(8,5)
                i < 8 -> m[8, i + 1] = mod                   // (8,7),(8,8)
                else -> m[8, size - 15 + i] = mod            // (8,22)~(8,28)
            }
        }
        // horizontal：y=8 这一行
        for (i in 0 until 15) {
            val mod = (bits shr i) and 1 == 1
            when {
                i < 8 -> m[size - i - 1, 8] = mod            // (28,8)~(21,8)
                i < 9 -> m[7, 8] = mod                       // (7,8)
                else -> m[15 - i - 1, 8] = mod               // (6,8)~(0,8)
            }
        }
        // 固定暗模块：位于 (x=8, y=size-8)
        m[8, size - 8] = true
    }
}
