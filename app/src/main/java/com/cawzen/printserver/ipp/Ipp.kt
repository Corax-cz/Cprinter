package com.cawzen.printserver.ipp

import java.io.ByteArrayOutputStream

/**
 * IPP 协议的二进制编码（RFC 8010）与最小语义（RFC 8011）。
 *
 * 为什么自己写而不是引依赖：整个协议就是「标签-长度-值」的 TLV 串，
 * 需要的能力不到规范的 5%；而 CUPS 客户端（Windows 的 IPP 端口、macOS 的
 * `ipptool`、Linux 的 `ippfind`）对**响应格式**的宽容度很低——多一个没要求的
 * 属性没事，少一个必需属性或字节序写错就直接拒收。自己写才能保证逐字节可控。
 *
 * 一个 IPP 报文长这样：
 *
 * ```
 * 版本(2) 操作码/状态码(2) 请求号(4)
 * [ 组标签(1)  属性* ]*
 * 0x03 (end-of-attributes)
 * 文档数据...            ← 只有 Print-Job 有
 * ```
 *
 * 属性 = 值标签(1) 名字长度(2) 名字 值长度(2) 值。
 * 同名多值时，后续条目名字长度写 0 并沿用上一个名字（1setOf 的编码方式）。
 */
object Ipp {

    // ── 版本 ────────────────────────────────────────────────────────────────
    const val VERSION_1_1 = 0x0101
    const val VERSION_2_0 = 0x0200

    // ── 操作码 ──────────────────────────────────────────────────────────────
    //
    // 严格照 RFC 8011 §4.2.1 的分配表。这张表曾经整体错位一格：
    //
    //   0x0002 Print-Job        0x0005 Create-Job     0x0009 Get-Job-Attributes
    //   0x0003 Print-URI        0x0006 Send-Document   0x000A Get-Jobs
    //   0x0004 Validate-Job     0x0007 Send-URI        0x000B Get-Printer-Attributes
    //   0x0008 Cancel-Job       0x003B Close-Job
    //
    // 当时 Validate/Create/Send-Document 三个连着写成 0x0003/0x0004/0x0005，
    // 于是 iOS 发 Create-Job(0x0005) 被当成 Send-Document 处理，发
    // Send-Document(0x0006) 落到 Send-URI 分支回「不支持」—— 整条 AirPrint
    // 作业流程从第一步就是错位的。这种错**不会报错**，只会表现成
    // 「点了打印没反应」，所以必须对照规范逐条核对，不能靠邻居推断。
    const val OP_PRINT_JOB = 0x0002
    const val OP_PRINT_URI = 0x0003
    const val OP_VALIDATE_JOB = 0x0004
    const val OP_CREATE_JOB = 0x0005
    const val OP_SEND_DOCUMENT = 0x0006
    const val OP_SEND_URI = 0x0007
    /**
     * Close-Job = 0x003B。
     *
     * 这里原先写成 0x0007，是个**标准错误**：0x0007 是 Send-URI，而 Close-Job
     * 是后期扩展的操作码，被放在 0x003B。自家工具和服务器用同一个错常量时一切
     * 正常，所以测试一直是绿的；但 iOS 的 printd 发的是标准值 0x003B，会被
     * `when` 落到 else 分支回 `server-error-operation-not-supported`，
     * 表现出来就是作业卡在 processing。
     */
    const val OP_CLOSE_JOB = 0x003B
    const val OP_CANCEL_JOB = 0x0008
    const val OP_GET_JOB_ATTRIBUTES = 0x0009
    const val OP_GET_JOBS = 0x000A
    const val OP_GET_PRINTER_ATTRIBUTES = 0x000B
    const val OP_HOLD_JOB = 0x000C
    const val OP_RELEASE_JOB = 0x000D
    const val OP_RESTART_JOB = 0x000E
    const val OP_PAUSE_PRINTER = 0x0010
    const val OP_RESUME_PRINTER = 0x0011
    const val OP_PURGE_JOBS = 0x0012
    const val OP_CUPS_GET_DEFAULT = 0x4001
    const val OP_CUPS_GET_PRINTERS = 0x4002

    // ── 状态码 ──────────────────────────────────────────────────────────────
    const val STATUS_OK = 0x0000
    const val STATUS_OK_IGNORED = 0x0001
    const val STATUS_BAD_REQUEST = 0x0400
    const val STATUS_FORBIDDEN = 0x0401
    const val STATUS_NOT_POSSIBLE = 0x0404
    const val STATUS_NOT_FOUND = 0x0406
    const val STATUS_DOCUMENT_FORMAT_NOT_SUPPORTED = 0x040A
    const val STATUS_ATTRIBUTES_NOT_SUPPORTED = 0x040B
    const val STATUS_VERSION_NOT_SUPPORTED = 0x0503
    const val STATUS_INTERNAL_ERROR = 0x0500
    const val STATUS_OPERATION_NOT_SUPPORTED = 0x0501
    const val STATUS_NOT_ACCEPTING_JOBS = 0x0506

    // ── 组标签（分隔符）────────────────────────────────────────────────────
    const val GROUP_OPERATION = 0x01
    const val GROUP_JOB = 0x02
    const val GROUP_END = 0x03
    const val GROUP_PRINTER = 0x04
    const val GROUP_UNSUPPORTED = 0x05

    // ── 值标签 ──────────────────────────────────────────────────────────────
    const val TAG_INTEGER = 0x21
    const val TAG_BOOLEAN = 0x22
    const val TAG_ENUM = 0x23
    const val TAG_OCTET_STRING = 0x30
    const val TAG_DATE_TIME = 0x31
    const val TAG_RESOLUTION = 0x32
    const val TAG_RANGE = 0x33
    // 集合（RFC 8010 §3.1.6）。CUPS 里 media-col-*、printer-input-tray 全是这个结构，
    // iOS 的打印面板靠它搭纸张/进纸盒模型 —— 缺了整个 collection 就只能「点不动」。
    const val TAG_BEG_COLLECTION = 0x34
    const val TAG_END_COLLECTION = 0x37
    /** 集合成员名专用 tag（不是普通 name，混用会让严格解析器直接判报文损坏）。 */
    const val TAG_MEMBER_NAME = 0x4A
    const val TAG_TEXT = 0x41
    const val TAG_NAME = 0x42
    const val TAG_KEYWORD = 0x44
    const val TAG_URI = 0x45
    const val TAG_CHARSET = 0x47
    const val TAG_LANGUAGE = 0x48
    const val TAG_MIME = 0x49

    /**
     * 无论 `requested-attributes` 写了什么都必须返回的属性。
     *
     * RFC 8011 §4.1.4 规定：响应里操作属性组的前两个属性必须是
     * `attributes-charset` 与 `attributes-natural-language`。客户端通常不会
     * 点名要它们（它以为服务端肯定给），所以过滤时必须豁免 —— 否则会构造出
     * 一份「开头就没有 charset」的报文，严格的客户端直接判非法。
     */
    val MANDATORY_ALWAYS = setOf("attributes-charset", "attributes-natural-language")

    // ── 打印机/作业状态枚举 ────────────────────────────────────────────────
    const val PRINTER_STATE_IDLE = 3
    const val PRINTER_STATE_PROCESSING = 4
    const val PRINTER_STATE_STOPPED = 5

    const val JOB_STATE_PENDING = 3
    const val JOB_STATE_PROCESSING = 5
    const val JOB_STATE_CANCELED = 7
    const val JOB_STATE_ABORTED = 8
    const val JOB_STATE_COMPLETED = 9

    // ────────────────────────────────────────────────────────────────────────

    /** 单个属性的值。保留原始字节，按标签解释。 */
    class Value(val tag: Int, val bytes: ByteArray) {

        /** 整数/枚举 → Int；布尔 → 0/1；其它按 UTF-8 解释后转 Int（尽力而为）。 */
        fun asInt(): Int? = when {
            tag == TAG_INTEGER || tag == TAG_ENUM || tag == TAG_BOOLEAN -> when {
                bytes.size >= 4 -> be32(bytes, 0)
                bytes.size == 1 -> bytes[0].toInt() and 0xFF
                else -> null
            }
            else -> asString().toIntOrNull()
        }

        fun asString(): String = when {
            tag == TAG_RESOLUTION && bytes.size >= 9 -> {
                val x = be32(bytes, 0)
                val y = be32(bytes, 4)
                val units = bytes[8].toInt() and 0xFF
                "$x x $y " + if (units == 3) "dpi" else "dpcm"
            }
            else -> String(bytes, Charsets.UTF_8)
        }

        fun asBool(): Boolean? = if (tag == TAG_BOOLEAN) bytes.firstOrNull()?.let { it.toInt() != 0 } else null
    }

    class Group(val tag: Int, val attrs: Map<String, List<Value>>)

    class Request(
        val version: Int,
        val operationId: Int,
        val requestId: Int,
        val groups: List<Group>,
        /** end-of-attributes 之后第一字节的偏移；没有文档时等于报文长度。 */
        val dataOffset: Int,
        private val raw: ByteArray,
        /**
         * 是否真的读到了 end-of-attributes 标记。
         *
         * 流式读取时先只读报文开头一小段，如果属性组比这段还长，解析会走到
         * 「没遇到 0x03 就结束」的容错分支 —— 此时 dataOffset 等于缓冲区长度，
         * 拿它去切文档会把属性组的尾巴当成文档内容。所以要能区分这两种情况。
         */
        val complete: Boolean = true,
    ) {
        val versionMajor: Int get() = (version shr 8) and 0xFF

        fun document(): ByteArray =
            if (dataOffset >= raw.size) ByteArray(0)
            else raw.copyOfRange(dataOffset, raw.size)

        /** 取操作属性里的第一个同名字符串值。 */
        fun operationValue(name: String): Value? =
            groups.firstOrNull { it.tag == GROUP_OPERATION }?.attrs?.get(name)?.firstOrNull()

        fun operationString(name: String): String? = operationValue(name)?.asString()

        /** 请求里显式列出的 requested-attributes（1setOf keyword）；空表示「全都要」。 */
        fun requestedAttributes(): List<String> =
            groups.firstOrNull { it.tag == GROUP_OPERATION }
                ?.attrs?.get("requested-attributes")
                ?.map { it.asString() }
                ?: emptyList()

        /**
         * 客户端要求的双面设置 → 我们的 duplex 布尔。
         * 不认识或缺省时返回 null，由调用方回落到界面设置。
         */
        /**
         * IPP `sides` 的原始值，没发就是 null。
         *
         * 单独暴露是为了打日志：`requestedDuplex()` 把「没发」「发了不认识的值」
         * 「明确单面」都折成 Boolean?，日志里区分不出来 —— 而排查双面失效时
         * 「iOS 到底发没发 sides」正是第一个要回答的问题。
         *
         * 遍历**所有**组而不是只看第一个 operation group：RFC 8011 规定
         * Print-Job 的 job template 属性在 operation group 里，但实测不同
         * iOS 版本会把 `sides` 放进单独的 job attributes 组。只认第一个组的话，
         * 属性明明在报文里却被读成「未声明」，双面作业静默变单面。
         */
        fun sidesText(): String? {
            for (g in groups) {
                val v = g.attrs["sides"]?.firstOrNull() ?: continue
                return v.asString()
            }
            return null
        }

        fun requestedDuplex(): Boolean? {
            val sides = sidesText() ?: return null
            return when (sides) {
                "one-sided" -> false
                "two-sided-long-edge", "two-sided-short-edge" -> true
                else -> null
            }
        }

        /**
         * IPP `copies`（integer），作业份数。没发就是 null。
         *
         * 同样遍历所有组：不同客户端把 job template 属性放在哪个组并不统一。
         *
         * 拿到之后由 PCL 层的 `ESC&l#X` 让打印机自己重复整个作业 —— 数据量不变。
         * 若改成在应用层把每页编码 N 遍，600dpi 双页作业 288KB 会被放大 N 倍，
         * 而 USB 全程只有约 1.5MB/s（bulkTransfer 单次 ≤16384 字节）。
         */
        fun requestedCopies(): Int? {
            for (g in groups) {
                val v = g.attrs["copies"]?.firstOrNull() ?: continue
                return v.asInt()
            }
            return null
        }
    }

    // ── 解析 ────────────────────────────────────────────────────────────────

    /** 解析失败返回 null。网络来的字节必须当作不可信输入，任何越界都只能返回 null。 */
    fun parse(raw: ByteArray): Request? {
        if (raw.size < 8) return null
        val version = be16(raw, 0)
        val operation = be16(raw, 2)
        val requestId = be32(raw, 4)

        val groups = ArrayList<Group>()
        var currentTag = -1
        var current = LinkedHashMap<String, MutableList<Value>>()
        var lastName = ""

        fun flush() {
            if (currentTag >= 0) groups += Group(currentTag, current)
            current = LinkedHashMap()
        }

        var p = 8
        while (p < raw.size) {
            val tag = raw[p].toInt() and 0xFF
            p++
            if (tag == GROUP_END) {
                flush()
                // 校验：Print-Job 没有文档是合法的（空作业），所以不在这里报错。
                return Request(version, operation, requestId, groups, p, raw, complete = true)
            }
            if (tag < 0x10) {           // 其它分隔符：开新组
                flush()
                currentTag = tag
                lastName = ""
                continue
            }
            // 值条目。名字长度 2 + 值长度 2 是硬性要求。
            if (p + 2 > raw.size) return null
            val nameLen = be16(raw, p); p += 2
            if (p + nameLen > raw.size) return null
            val name = if (nameLen == 0) lastName else String(raw, p, nameLen, Charsets.UTF_8)
            p += nameLen
            if (p + 2 > raw.size) return null
            val valueLen = be16(raw, p); p += 2
            if (p + valueLen > raw.size) return null
            val value = raw.copyOfRange(p, p + valueLen)
            p += valueLen

            if (currentTag < 0) {       // 第一个分隔符之前出现值：容错成操作组
                currentTag = GROUP_OPERATION
            }
            lastName = name
            current.getOrPut(name) { ArrayList() } += Value(tag, value)
        }
        // 没有遇到 0x03 就结束了：协议违规，但也可能只是客户端偷懒，容错处理。
        flush()
        return Request(version, operation, requestId, groups, raw.size, raw, complete = false)
    }

    // ── 构造 ────────────────────────────────────────────────────────────────

    /** 响应构造器。顺序由调用方控制——客户端对「必需属性」的检查是顺序无关的。 */
    class Writer(
        private val status: Int = STATUS_OK,
        private val requestId: Int = 1,
        private val version: Int = VERSION_2_0,
        /**
         * 客户端在 `requested-attributes` 里点名要的属性集合。
         *
         * `null` 表示不过滤（等同于客户端要了 `all`）。RFC 8011 §4.2.5 要求
         * 服务端**必须**只回客户端点名的那些属性；以前我们一律回全量，理由是
         * 「多给不会错」。实际上多给是有代价的：客户端只要 4 项时也会收到
         * 7000 字节、含三条 media-col collection 和 octetString 托盘的报文，
         * 任何一个属性让它的解析器不满意，整份响应就作废 —— 而它并不会告诉
         * 你哪一项不满意。过滤之后，小请求得到的就是小报文。
         */
        private val wanted: Set<String>? = null,
    ) {
        private val out = ByteArrayOutputStream(4096)

        /**
         * 正在丢弃的 collection 层数。
         *
         * 一条 collection 由十几个 `attr()` 调用拼成，被过滤掉时必须把它们
         * **整块**丢掉，而且 beg/end 要配对 —— 只丢 begCollection 会留下孤立的
         * endCollection(0x37)，那比不过滤更糟：客户端会在 tag 流上直接失配。
         */
        private var skipDepth = 0

        /**
         * 上一个写出的属性名。用于把同一属性的第 2..n 个值写成空名（`1setOf` 规则），
         * 详见 [attr] 里的注释。`group()` 会把它清空。
         */
        private var prevName: String? = null

        init {
            u8((version shr 8) and 0xFF)
            u8(version and 0xFF)
            u16(status)
            u32(requestId)
        }

        fun group(tag: Int): Writer {
            // 组一变，属性名序列就重新开始 —— 不同组里出现同名属性是合法的。
            prevName = null
            u8(tag)
            return this
        }

        fun attr(tag: Int, name: String, value: ByteArray): Writer {
            if (skipDepth > 0) {
                when (tag) {
                    TAG_BEG_COLLECTION -> skipDepth++
                    TAG_END_COLLECTION -> skipDepth--
                }
                return this
            }
            // 「永远要有」的两项：响应操作组的前两个属性必须是它们（RFC 8011 §4.1.4），
            // 客户端不会（也不该）在 requested-attributes 里点名要。
            if (wanted != null && name.isNotEmpty() && name !in wanted && name !in MANDATORY_ALWAYS) {
                if (tag == TAG_BEG_COLLECTION) skipDepth = 1
                return this
            }
            // `1setOf` 的写法：**只有第一个值带属性名**，后续值的 name-length 必须是 0
            // （RFC 8010 §3.1.5）。这里以前是反着写的（每个值都写全名），自家解析器
            // 按「同名合并」读所以一直是绿的，但真实的 CUPS / ipp 库只认第一个值：
            //   · `document-format-supported` 只剩 `image/urf` → Android 默认打印服务
            //     （com.android.bips）的 `canPrintPDF` 变成 0，它只会产 PDF/PCLm/PWG，
            //     于是判定「这台机器打不出我能做的格式」，**整台打印机不进系统列表**；
            //   · `sides-supported` 只剩 `one-sided` → 双面选项凭空消失；
            //   · `media-supported` 只剩 A4 → Letter/Legal 消失。
            // 客户端不会报错，只会安静地少给你东西 —— 这是最难查的一类。
            val writeName = if (name.isNotEmpty() && name == prevName) "" else name
            if (name.isNotEmpty()) prevName = name
            val n = writeName.toByteArray(Charsets.UTF_8)
            u8(tag)
            u16(n.size)
            out.write(n)
            u16(value.size)
            out.write(value)
            return this
        }

        fun int(name: String, v: Int) = attr(TAG_INTEGER, name, be32Bytes(v))
        fun enumV(name: String, v: Int) = attr(TAG_ENUM, name, be32Bytes(v))
        fun bool(name: String, v: Boolean) = attr(TAG_BOOLEAN, name, byteArrayOf(if (v) 1 else 0))
        fun keyword(name: String, v: String) = attr(TAG_KEYWORD, name, v.toByteArray(Charsets.UTF_8))
        fun text(name: String, v: String) = attr(TAG_TEXT, name, v.toByteArray(Charsets.UTF_8))
        fun nameV(name: String, v: String) = attr(TAG_NAME, name, v.toByteArray(Charsets.UTF_8))
        fun uri(name: String, v: String) = attr(TAG_URI, name, v.toByteArray(Charsets.UTF_8))
        fun charset(name: String, v: String) = attr(TAG_CHARSET, name, v.toByteArray(Charsets.UTF_8))
        fun language(name: String, v: String) = attr(TAG_LANGUAGE, name, v.toByteArray(Charsets.UTF_8))
        fun mime(name: String, v: String) = attr(TAG_MIME, name, v.toByteArray(Charsets.UTF_8))
        fun octets(name: String, v: ByteArray) = attr(TAG_OCTET_STRING, name, v)

        /**
         * 开一个集合值（value-tag 0x34，value 长度为 0）。
         *
         * 1setOf collection 的第二个元素起名字长度必须为 0 —— 和普通多值属性一样，
         * 名字只在第一个元素上出现。
         */
        fun begCollection(name: String) = attr(TAG_BEG_COLLECTION, name, ByteArray(0))

        /** 集合成员的名字（tag 0x4A，自身名字长度为 0）。随后紧跟成员值。 */
        fun memberName(n: String) = attr(TAG_MEMBER_NAME, "", n.toByteArray(Charsets.UTF_8))

        fun endCollection() = attr(TAG_END_COLLECTION, "", ByteArray(0))

        /** 集合里一个整数成员。 */
        fun memberInt(n: String, v: Int) = memberName(n).attr(TAG_INTEGER, "", be32Bytes(v))

        /** 集合里一个 keyword/name/text 成员。 */
        fun memberKeyword(n: String, v: String) =
            memberName(n).attr(TAG_KEYWORD, "", v.toByteArray(Charsets.UTF_8))

        fun memberText(n: String, v: String) =
            memberName(n).attr(TAG_TEXT, "", v.toByteArray(Charsets.UTF_8))

        fun memberNameValue(n: String, v: String) =
            memberName(n).attr(TAG_NAME, "", v.toByteArray(Charsets.UTF_8))

        fun resolution(name: String, xdpi: Int, ydpi: Int): Writer {
            val b = ByteArrayOutputStream(9)
            b.write(be32Bytes(xdpi))
            b.write(be32Bytes(ydpi))
            b.write(3)                  // 3 = dots per inch
            return attr(TAG_RESOLUTION, name, b.toByteArray())
        }

        fun range(name: String, lo: Int, hi: Int): Writer {
            val b = ByteArrayOutputStream(8)
            b.write(be32Bytes(lo))
            b.write(be32Bytes(hi))
            return attr(TAG_RANGE, name, b.toByteArray())
        }

        /**
         * dateTime 值（RFC 8011 §4.1.9），固定 11 个字节：
         * 年(2) 月(1) 日(1) 时(1) 分(1) 秒(1) 十分之一秒(1)
         * UTC 方向字符(1，`+`/`-`) UTC 小时(1) UTC 分钟(1)。
         *
         * 注意方向字符那一字节是 ASCII 的 '+'/'-'，不是数值 —— 写成 0/1 会让整个
         * 属性的长度对、内容错，客户端解析出 1970 年或直接丢弃。
         */
        fun dateTime(name: String, millis: Long): Writer {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = millis
            val offMin = (cal.get(java.util.Calendar.ZONE_OFFSET) +
                cal.get(java.util.Calendar.DST_OFFSET)) / 60000
            val b = ByteArrayOutputStream(11)
            val year = cal.get(java.util.Calendar.YEAR)
            b.write((year shr 8) and 0xFF)
            b.write(year and 0xFF)
            b.write(cal.get(java.util.Calendar.MONTH) + 1)
            b.write(cal.get(java.util.Calendar.DAY_OF_MONTH))
            b.write(cal.get(java.util.Calendar.HOUR_OF_DAY))
            b.write(cal.get(java.util.Calendar.MINUTE))
            b.write(cal.get(java.util.Calendar.SECOND))
            b.write(cal.get(java.util.Calendar.MILLISECOND) / 100)
            b.write(if (offMin < 0) '-'.code else '+'.code)
            b.write((kotlin.math.abs(offMin) / 60) and 0xFF)
            b.write(kotlin.math.abs(offMin) % 60)
            return attr(TAG_DATE_TIME, name, b.toByteArray())
        }

        /**
         * 多值属性（1setOf）。
         *
         * RFC 8010 §3.1.5：1setOf「编码成多个同名属性」—— **每一条都要带完整
         * 属性名**。早期这里只在第一条上写名字、后续写空串，是个致命的编码错误：
         * 空名字是 collection 成员的规则，不是 1setOf 的规则。
         *
         * 后果极其隐蔽 —— 报文本身能解析、不报错，但客户端只认第一条，于是：
         *   `sides-supported`     只剩 `one-sided`（而 TXT 说 Duplex=T）
         *   `print-quality-supported` 只剩 `3`（而 default 是 4，不在集合内）
         *   `operations-supported`  只剩 `Print-Job`
         * iOS 据此判定「打印机自述自相矛盾」，一个作业都不提交 ——
         * 现象就是打印按钮亮着、点下去毫无反应。空名多值是真凶。
         */
        fun attrMulti(tag: Int, name: String, values: List<ByteArray>): Writer {
            values.forEach { attr(tag, name, it) }
            return this
        }

        fun keywordList(name: String, values: List<String>): Writer {
            values.forEach { keyword(name, it) }
            return this
        }

        fun enumList(name: String, values: List<Int>): Writer {
            values.forEach { enumV(name, it) }
            return this
        }

        fun resolutionList(name: String, values: List<Pair<Int, Int>>): Writer {
            values.forEach { (x, y) -> resolution(name, x, y) }
            return this
        }

        /** 结束报文。若传了 document 会接在 0x03 之后（Print-Job 的响应不带文档）。 */
        fun build(): ByteArray {
            u8(GROUP_END)
            return out.toByteArray()
        }

        private fun u8(v: Int) = out.write(v and 0xFF)
        private fun u16(v: Int) { u8(v shr 8); u8(v) }
        private fun u32(v: Int) {
            u8(v shr 24); u8(v shr 16); u8(v shr 8); u8(v)
        }
    }

    // ── 字节工具 ────────────────────────────────────────────────────────────

    fun be16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    fun be32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    fun be32Bytes(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )
}
