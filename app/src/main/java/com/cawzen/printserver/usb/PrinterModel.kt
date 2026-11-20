package com.cawzen.printserver.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice

/**
 * 支持的打印机型号（L2）。
 *
 * 这里有两件不同的事，必须分开说清楚，否则界面上会变成一句含糊的「支持很多机型」：
 *
 * 1. **[Driver.BRLASER]（渲染）** —— brlaser 是 Brother 黑白激光机芯的专用编码器。
 *    「支持哪些机型」的准确答案就是 brlaser 官方表（[CATALOG]），再加上**采用同一
 *    Brother 机芯的贴牌机**：联想、富士胶片、东芝、柯尼卡美能达都在贴兄弟的机芯，
 *    编码完全一致，所以它们不是「另外支持」，而是同一台机器的另一个名字
 *    （见每个型号的 [Model.aliases]）。
 * 2. **[Driver.PASSTHROUGH]（直通）** —— 不渲染，把客户端已经排好版的 PCL /
 *    PostScript 字节原样转给 USB。这一条**与品牌无关**：HP、佳能、京瓷、理光、
 *    奔图……只要电脑上装了厂商驱动并指向这台网关，就能打。代价是 iPhone 用不了
 *    （iOS 只会送 URF 光栅，而生成各家私有光栅是不可能的）。
 *
 * 为什么不能只靠型号名：brlaser 自己靠 IEEE 1284 DeviceID 匹配型号，而 USB 直连
 * 的这类机芯拿不到 DeviceID（`GET_PORT_STATUS` 都返回 -1），只能退到 VID/PID。
 * 而 VID/PID 表是残缺的 —— 同一台机器在不同固件、不同 OEM 贴牌下的 PID 不一样，
 * 联想 LJ2600D 就是 17ef:5411，表里根本没有。所以识别分三层：
 *
 *   1. 精确表：实测或权威来源确认过的 VID:PID → 直接命名。
 *   2. 厂商级：VID 属于 Brother / Lenovo 且接口是打印机类 → 用通用名接入。
 *   3. 手动指定：用户在设置里挑一个型号（[CATALOG] 里任意一项），只要设备像打印机
 *      就按这个型号接入。这一层覆盖「表里没有、但确实是同一个机芯」的 OEM 机器，
 *      也是「通用直通」模式的入口。
 *
 * 型号选择**不影响编码参数**：分辨率、双面、纸张都由作业参数决定，brlaser 内核
 * 对所有机型输出同一套 PCL。它影响的是显示名、日志归因、以及走哪条驱动路径。
 */
object PrinterModel {

    /**
     * 驱动路径。
     *
     * 这个枚举是「能支持多少机型」这个问题的真正答案 —— 不是型号表有多长。
     */
    enum class Driver {
        /** brlaser 渲染：PDF/URF/PWG → 兄弟机芯 PCL。只对兄弟系（含贴牌）有效。 */
        BRLASER,

        /**
         * 原样直通：客户端送什么就转什么。
         *
         * 适用于任何吃 PCL5/PCL6/PostScript 的打印机（HP、佳能、京瓷、理光、
         * 兄弟自己的 PCL 模式……），因为它根本不需要我们理解页面内容。
         */
        PASSTHROUGH,
    }

    /** 列表里的一项。 */
    data class Model(
        /** 稳定 id（持久化用，不要改）。 */
        val id: String,
        /** 显示名。 */
        val name: String,
        /** 打印引擎族，用于界面分组。 */
        val family: String,
        /**
         * 机芯是否带自动双面（brlaser 的 `Duplex rotated` 标注，或本项目实测）。
         *
         * 这是**能力标注**，不是功能开关：真打双面仍然由客户端在作业里指定
         * `sides=two-sided-long-edge`，我们只负责把它翻成 `ESC&l1S`。标出来是因为
         * 用户在选机型时最想知道的就是「这台能不能双面」，而 brlaser 的表里
         * 这个信息藏在 PPD 里，界面上看不到。
         */
        val duplex: Boolean = false,
        /**
         * 采用同一机芯的贴牌机型。
         *
         * 来源是耗材/机芯交叉表（同一支粉盒能装的机器 = 同一套打印引擎），而不是
         * 外观相似度：粉盒的物理接口、显影辊、齿轮传动比由机芯决定，能换就意味着
         * 是同一台机器。所以这些名字下面的 PCL 编码和正牌 Brothers 完全一致。
         */
        val aliases: List<String> = emptyList(),
        /** 走哪条驱动路径。 */
        val driver: Driver = Driver.BRLASER,
        /** 一句话补充说明（列表副行显示）。 */
        val note: String = "",
    )

    /**
     * 完整机型表。
     *
     * brlaser 部分是**两个**官方文件的并集，README 本身不全：
     *
     *  - `brlaser.drv.in`（CUPS driver 定义，每个 PPD 一个 `ModelName`）—— 34 项，
     *    权威。
     *  - `README.md` 的 "reported to work with these printers" —— 比 drv.in 多一项
     *    `MFC-7840W`，少一项 `DCP-7080D`。
     *
     * 再加三款本项目实测/上游缺失的：
     *  - `HL-2240D`：上游只写到「HL-2220 series」，但本项目的被测机就是它
     *    （USB `04f9:0045`），编码路径与 HL-2220 完全一致；
     *  - 联想 `LJ2600D`（`17ef:5411`，HL-2240D 同机芯），已实测打印与双面；
     *  - 联想 `M7605D` 上游 README 有、drv.in 没有，保留。
     *
     * 第一项是「通用直通」，它不是 brlaser 机型，所以数量统计要用 [BRLASER_COUNT]。
     */
    val CATALOG: List<Model> = listOf(
        // ── 通用（不属于兄弟系，靠直通）────────────────────────────────────
        Model(
            id = "generic-passthrough",
            name = "通用 PCL / PostScript 打印机",
            family = "通用",
            driver = Driver.PASSTHROUGH,
            note = "HP / 佳能 / 京瓷 / 理光 / 奔图等：不渲染，原样转发客户端排好版的数据",
        ),

        // ── DCP 系列（14 款，其中 7 款带双面）─────────────────────────────
        Model("dcp-1510", "Brother DCP-1510 系列", "DCP"),
        Model("dcp-1600", "Brother DCP-1600 系列", "DCP"),
        Model("dcp-7030", "Brother DCP-7030", "DCP"),
        Model("dcp-7040", "Brother DCP-7040", "DCP"),
        Model("dcp-7055", "Brother DCP-7055", "DCP"),
        Model("dcp-7055w", "Brother DCP-7055W", "DCP"),
        Model(
            "dcp-7060d", "Brother DCP-7060D", "DCP", duplex = true,
            aliases = listOf("联想 M7600D"),
        ),
        Model("dcp-7065dn", "Brother DCP-7065DN", "DCP", duplex = true),
        Model("dcp-7080", "Brother DCP-7080", "DCP"),
        Model(
            "dcp-7080d", "Brother DCP-7080D", "DCP", duplex = true,
            // 这一组是同一套 30ppm 机芯（兄弟 TN-2325 / 联想 LT-2451H / 富士 P228 /
            // 东芝 T-3003C / 柯美 同号粉盒），贴牌范围最广，所以名字最多。
            aliases = listOf(
                "联想 M7605D", "联想 M7405D", "联想 M7615DNA", "联想 M7675DXF",
                "联想 LJ2605D", "联想 LJ2655DN",
                "富士胶片 DocuPrint P228db", "富士胶片 M228b",
                "东芝 e-STUDIO 301DN",
                "柯尼卡美能达 bizhub 3000MF",
            ),
        ),
        Model("dcp-l2500d", "Brother DCP-L2500D 系列", "DCP", duplex = true),
        Model("dcp-l2520d", "Brother DCP-L2520D 系列", "DCP", duplex = true),
        Model("dcp-l2520dw", "Brother DCP-L2520DW 系列", "DCP", duplex = true),
        Model("dcp-l2540dw", "Brother DCP-L2540DW 系列", "DCP", duplex = true),

        // ── HL 系列（14 款，其中 8 款带双面）──────────────────────────────
        Model("hl-1110", "Brother HL-1110 系列", "HL"),
        Model("hl-1200", "Brother HL-1200 系列", "HL"),
        Model("hl-2030", "Brother HL-2030 系列", "HL"),
        Model("hl-2140", "Brother HL-2140 系列", "HL"),
        Model("hl-2220", "Brother HL-2220 系列", "HL"),
        // 本项目实测机。联想 LJ2600D 与它是同一款产品（百度百科原话），
        // LJ2650DN 是同一机芯加网口的版本。
        Model(
            "hl-2240d", "Brother HL-2240D 系列", "HL", duplex = true,
            aliases = listOf("联想 LJ2600D", "联想 LJ2650DN"),
        ),
        Model("hl-2270dw", "Brother HL-2270DW 系列", "HL", duplex = true),
        Model("hl-5030", "Brother HL-5030 系列", "HL"),
        Model("hl-l2300d", "Brother HL-L2300D 系列", "HL", duplex = true),
        Model("hl-l2320d", "Brother HL-L2320D 系列", "HL", duplex = true),
        Model("hl-l2340d", "Brother HL-L2340D 系列", "HL", duplex = true),
        Model("hl-l2360d", "Brother HL-L2360D 系列", "HL", duplex = true),
        Model("hl-l2375dw", "Brother HL-L2375DW 系列", "HL", duplex = true),
        Model("hl-l2390dw", "Brother HL-L2390DW", "HL", duplex = true),

        // ── MFC 系列（8 款）──────────────────────────────────────────────
        Model("mfc-1910w", "Brother MFC-1910W", "MFC"),
        Model("mfc-7240", "Brother MFC-7240", "MFC"),
        Model(
            "mfc-7360n", "Brother MFC-7360N", "MFC",
            aliases = listOf("联想 M7450F"),
        ),
        Model("mfc-7365dn", "Brother MFC-7365DN", "MFC", duplex = true),
        Model("mfc-7420", "Brother MFC-7420", "MFC"),
        Model(
            "mfc-7460dn", "Brother MFC-7460DN", "MFC", duplex = true,
            aliases = listOf("联想 M7650DF", "联想 M7650DNF"),
        ),
        Model("mfc-7840w", "Brother MFC-7840W", "MFC"),
        Model("mfc-l2710dw", "Brother MFC-L2710DW 系列", "MFC", duplex = true),
    )

    /** brlaser 能渲染的机型数（不含「通用直通」那一项）。 */
    val BRLASER_COUNT: Int = CATALOG.count { it.driver == Driver.BRLASER }

    /** 贴牌机型总数（只用于界面上一句「另外还覆盖 N 款贴牌机」）。 */
    val ALIAS_COUNT: Int = CATALOG.sumOf { it.aliases.size }

    /**
     * 旧 id 兼容。
     *
     * v0.5.x 里联想是独立条目（`lenovo-lj2600d` / `lenovo-m7605d`），现在并成了
     * 兄弟型号的别名。用户的选择存在 SharedPreferences 里，不能让旧值变成「未识别」。
     */
    private val LEGACY_IDS = mapOf(
        "lenovo-lj2600d" to "hl-2240d",
        "lenovo-m7605d" to "dcp-7080d",
    )

    fun byId(id: String?): Model? {
        val key = id?.takeIf { it.isNotBlank() } ?: return null
        return CATALOG.firstOrNull { it.id == key }
            ?: CATALOG.firstOrNull { it.id == LEGACY_IDS[key] }
    }

    /** 一台被识别出来的打印机。 */
    data class Info(
        /** 显示给用户的型号名。 */
        val displayName: String,
        /** 精确匹配了型号表（VID/PID 命中）。 */
        val exact: Boolean,
        /** 用户在设置里手动指定的型号（不是自动识别出来的）。 */
        val manual: Boolean = false,
        /** 命中的机型 id（自动识别未命中时为 null）。 */
        val modelId: String? = null,
        /** 走哪条驱动路径。默认渲染，因为自动识别只认兄弟系。 */
        val driver: Driver = Driver.BRLASER,
    )

    /** 精确型号表：VID:PID → 型号 id。只有实测或权威来源确认过的才进这里。 */
    private val EXACT: Map<Int, Map<Int, String>> = mapOf(
        // Lenovo LJ2600D（Brother HL-2240D 同构机，实测 USB 端点）
        0x17EF to mapOf(0x5411 to "hl-2240d"),
        // Brother HL-2240 series（USB ID Repository 确认 04f9:0045）
        0x04F9 to mapOf(0x0045 to "hl-2240d"),
    )

    /** brlaser 覆盖的厂商 VID。这些厂商的黑白激光机都值得尝试。 */
    private val SUPPORTED_VENDORS = setOf(
        0x04F9, // Brother Industries
        0x17EF, // Lenovo（OEM Brother 机芯）
    )

    private fun vendorName(vid: Int): String = when (vid) {
        0x04F9 -> "Brother"
        0x17EF -> "联想"
        else -> "未知厂商"
    }

    /**
     * 识别一台 USB 设备是否是可支持的打印机，以及它叫什么。
     *
     * @param overrideId 用户在设置里手动指定的机型 id；null/空 = 自动识别。
     * @return null 表示不支持（不是打印机，或厂商不在 brlaser 覆盖范围）。
     */
    fun identify(device: UsbDevice, overrideId: String? = null): Info? {
        val vid = device.vendorId
        val pid = device.productId

        // 0. 手动指定的型号优先。厂商判断让路 —— OEM 贴牌机的 VID 五花八门，
        //    这正是需要手选的场景；直通模式更是完全不看厂商。
        byId(overrideId)?.let { m ->
            // 两种驱动都只要求「这台设备像打印机」。直通模式额外放宽的是**厂商**
            // ——不看 VID、不看 PID，因为 HP / 佳能 / 京瓷的号码我们一个都没录。
            return if (looksLikePrinter(device)) {
                Info(m.name, exact = false, manual = true, modelId = m.id, driver = m.driver)
            } else {
                null
            }
        }

        // 1. 精确型号表
        EXACT[vid]?.get(pid)?.let { id ->
            val name = byId(id)?.name ?: "Brother 激光打印机"
            return Info(name, exact = true, modelId = id)
        }

        // 2. 厂商级：必须同时是打印机类设备，避免把同厂商的扫描仪/网卡当打印机
        if (vid in SUPPORTED_VENDORS && isPrinterClass(device)) {
            return Info("${vendorName(vid)} 激光打印机", exact = false)
        }

        return null
    }

    /** 是否打印机类设备（interface class 0x07）。 */
    fun isPrinterClass(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == 0x07) return true
        }
        return false
    }

    /** 有没有 Bulk OUT 端点 —— 能不能真的把字节送进去的最低条件。 */
    private fun hasBulkOut(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    ep.direction == UsbConstants.USB_DIR_OUT
                ) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * 这台设备能不能当打印机用。
     *
     * 手动指定 / 通用直通的场景不能只看「有 Bulk OUT」：U 盘、手机（MTP）、
     * 视频采集卡**全都有** Bulk OUT，而把 PCL 灌进 U 盘不会报错，只会静默写坏
     * 它的文件系统 —— 这是最坏的一种失败，因为它看起来像是「打印成功」。
     *
     * 判定顺序：
     *  1. 接口类 0x07（Printer）→ 是。
     *  2. 没有任何 Bulk OUT → 不是。
     *  3. 出现了已知的非打印机接口类（大容量存储 / HID / 音频 / 视频 / 无线 /
     *     通信数据类）→ 不是。有些打印机把打印接口报成厂商自定义类 0xFF，
     *     所以不能只认 0x07，但也不能放过这些明确「不是打印机」的类。
     */
    private fun looksLikePrinter(device: UsbDevice): Boolean {
        if (isPrinterClass(device)) return true
        if (!hasBulkOut(device)) return false
        for (i in 0 until device.interfaceCount) {
            when (device.getInterface(i).interfaceClass) {
                0x01, // Audio
                0x03, // HID（键鼠）
                0x08, // Mass Storage（U 盘、移动硬盘）
                0x09, // Hub
                0x0A, // CDC Data（手机 MTP / 调制解调器）
                0x0B, // Smart Card
                0x0E, // Video（摄像头、采集卡）
                0xE0, // Wireless / 蓝牙
                -> return false
            }
        }
        return true
    }

    /**
     * 枚举当前所有 USB 设备，并标注哪台是可支持的。供诊断/向导展示。
     */
    fun describeDevices(devices: List<UsbDevice>, overrideId: String? = null): String {
        if (devices.isEmpty()) return "未检测到任何 USB 设备"
        return devices.joinToString("\n") { d ->
            val id = "VID=0x%04X PID=0x%04X".format(d.vendorId, d.productId)
            val info = identify(d, overrideId)
            when {
                info == null -> "$id ${d.deviceName ?: "?"}（不支持）"
                info.driver == Driver.PASSTHROUGH -> "$id ${info.displayName}（直通，不渲染）"
                info.manual -> "$id ${info.displayName}（手动指定）"
                info.exact -> "$id ${info.displayName} ✓ 支持"
                else -> "$id ${info.displayName}（brlaser 兼容，可尝试）"
            }
        }
    }
}
