package com.cawzen.printserver.mdns

import android.content.Context
import android.provider.Settings
import java.util.UUID

/**
 * AirPrint 广播内容（Bonjour TXT 记录 + IPP 属性里的同一批事实）。
 *
 * AirPrint 的发现条件不是「有 `_ipp._tcp` 就行」，而是四件事同时成立：
 *
 * 1. `_ipp._tcp` 服务带 **`_universal` 子类型**（PTR `_universal._sub._ipp._tcp.local`）。
 *    iOS 只列带这个子类型的打印机 —— 这正是系统 `NsdManager` 做不到的地方
 *    （它没有子类型 API），所以要自己发 mDNS。
 * 2. TXT 里有 `URF=`，且 `pdl` 里含 `image/urf`。**只声明 PDF 是列不出来的**：
 *    AirPrint 的模型是「客户端自己光栅化，打印机收 URF」，不认这件事的打印机
 *    在 iOS 里根本不会出现。
 * 3. `Color` / `Duplex` 与实际能力一致（F/T），否则 App 里的选项会给出打不出来的组合。
 * 4. `rp`（资源路径）与 IPP 路由一致，`adminurl` 指向能打开的页面。
 *
 * `URF=` 的每个 token 都要在 IPP 的 `urf-supported` 里出现一次（同一个事实两个协议
 * 各说一遍），所以这张表被两处共用。
 */
object AirPrintTxt {

    /**
     * 默认的服务实例名（用户没改名时用这个）。
     *
     * 真正发出去的名字来自 [com.cawzen.printserver.AppSettings.effectivePrinterName]，
     * 改名后 [com.cawzen.printserver.PrintService] 会注销重注册。这里保留常量只是
     * 给 [MdnsResponder] 的默认参数和一个兜底值，不要在业务代码里直接拿它当"当前名字"。
     */
    const val INSTANCE = "Cprinter"

    const val RP = "ipp/print"

    /**
     * URF 能力集合。
     *
     * - `W8` 8bpp 灰度。**刻意不声明 `SRGB24`**：这是台黑白激光机，
     *   `print-color-mode-supported` 也只写了 monochrome，两边一起说彩色
     *   会让 iOS 认为自述矛盾。而且 24bpp 的数据量是 3 倍 —— 600dpi A4 单页
     *   从 35MB 涨到 104MB，纯属自己给自己找 OOM。解码器本身仍能吃 24bpp，
     *   只是不主动邀请客户端这么送。
     * - `CP1` 份数、`IS1` 单纸盒、`MT1` 普通纸、`OB10` 单出纸口。
     *   `MT` 只写 `MT1`：IPP 侧 `media-type-supported` 只有 `stationery`（普通纸），
     *   写 `MT1-3-8` 等于同时宣称支持照片纸/其它介质，又是一处自述矛盾。
     * - `PQ3-4-5` 三档质量。
     * - `RS600` 分辨率。**只能写 600，不能写 `RS300-600`**：这是 HL-2240D 机芯，
     *   规格 2400×600 里的 2400 是纵向抖动插值，可选的光栅采样率只有 600 一档，
     *   没有 300dpi 模式。写成 `RS300-600`，iOS 会老老实实按 300dpi 渲染 URF 送过来，
     *   PJL 里就带上了 `SET RESOLUTION = 300`，打印机不认这一档 → **整页光栅被丢弃，
     *   吐一张白纸，还不报错**。这条和「打白纸」是直接因果关系。
     * - `DM1,DM3` 单面 + 双面长边。只写 `DM1` 会和 `Duplex=T`、`sides-supported` 打架：
     *   TXT 说能双面、URF 说只能单面，iOS 会挑一个它信得过的，另一处就白写了。
     * - `V1.4` 是格式版本号，必须带。
     *
     * 故意不声明 1200dpi：A4@1200 单页灰度 139MB，手机会 OOM，
     * 而 LJ2600D 的机芯本身也只到 600。
     */
    val URF_TOKENS = listOf(
        "W8", "CP1", "IS1", "MT1", "OB10",
        "PQ3-4-5", "RS600", "DM1", "DM3", "V1.4",
    )

    /** TXT 里的 `URF=` 值：逗号分隔，顺序即偏好。 */
    fun urfString(): String = URF_TOKENS.joinToString(",")

    /**
     * TXT 的 `pdl=`。
     *
     * 顺序照抄所有已知可用的配置 —— macOS 的 `dns-sd -P` 示例、Avahi 的
     * `_universal._sub._ipp._tcp` 服务文件、以及本机之前那套 CUPS，一律是
     * `application/pdf` 在前。顺序本身不影响功能，保留它只为减少变量。
     *
     * **必须与 IPP 的 `document-format-supported` 同源**（多一点不算矛盾，
     * 少一点就算）。这条以前只写了 pdf + urf，而 IPP 那边声明了 5 种格式 ——
     * 客户端拿 TXT 和 IPP 交叉核对时，两处不一致会被判成「同一台机器自述矛盾」。
     *
     * PCL / octet-stream 不写进来：TXT 的 `pdl` 是给「要不要用这台」做快速筛选用的，
     * 列了也不会有人选；URF 与 PDF 才是 AirPrint 真正会走的两条。
     */
    fun pdl(): String = "application/pdf,image/urf,image/pwg-raster"

    /**
     * 直通模式下的 `pdl=`。
     *
     * 不列 `image/urf` —— 那个格式**我们自己生成不了**。通用直通只是把客户端
     * 排好版的数据转给 USB，没有目标机芯的光栅编码器；把 urf 列上去，iOS 会
     * 高高兴兴送来 URF，然后我们只能拒收，用户看到的是「点了打印没反应」。
     */
    fun pdlPassthrough(): String =
        "application/pdf,application/vnd.hp-pcl,application/postscript"

    /**
     * 主机名标签（`<label>.local`）。
     *
     * 不用 `Build.MODEL`：里面有空格和中文，做 DNS 标签要转义，而且同型号手机
     * 会在局域网上撞名。用 ANDROID_ID 的散列取 4 位十六进制，稳定、够短、几乎不撞。
     */
    fun hostLabel(context: Context): String {
        val h = (androidId(context) + "|host").hashCode()
        return "wprint-" + "%04x".format(h and 0xFFFF)
    }

    /**
     * 打印机 UUID。
     *
     * 必须是**稳定**的：iOS 按 UUID 记住「这台打印机的默认纸张/单双面」，
     * 每次启动换一个 UUID 会让用户的设置反复丢失。用 ANDROID_ID 派生就稳定了。
     */
    fun printerUuid(context: Context): String =
        UUID.nameUUIDFromBytes(("wprint-ipp:" + androidId(context)).toByteArray()).toString()

    /**
     * 完整 TXT 记录。
     *
     * 键名大小写有讲究：`Color`/`Duplex` 是历史遗留的大写键，Apple 那边的解析器
     * 只认这个拼法；`URF`/`pdl`/`rp` 同理，改成小写会让打印机从列表里消失。
     */
    fun build(context: Context, ip: String, port: Int): LinkedHashMap<String, String> {
        val device = com.cawzen.printserver.usb.UsbPrinter.findSupportedPrinter(context)
        val model = device?.let {
            com.cawzen.printserver.usb.PrinterModel
                .identify(it, com.cawzen.printserver.usb.UsbPrinter.overrideModelId(context))
                ?.displayName
        }
            ?: "Brother 激光打印机"
        val gateway = com.cawzen.printserver.usb.UsbPrinter.driverOf(context) ==
            com.cawzen.printserver.usb.PrinterModel.Driver.PASSTHROUGH
        val txt = linkedMapOf(
        "txtvers" to "1",
        "qtotal" to "1",
        "rp" to RP,
        "ty" to model,
        "product" to "(Cprinter)",
        // `note` / `usb_MFG` / `usb_MDL` 已删除。它们只是展示用，但让 TXT 从
        // 374 涨到 429 字节，越过了 `NsdServiceInfo` 的 400 字节线：
        //
        //   Total length of all attributes exceeds 400 bytes; truncation may occur
        //
        // 一旦系统 mdnsd 真按这个长度截断，TXT 记录就是残的 —— iPhone 走的是
        // 我们自己发的完整 TXT 所以看不出来，但 Windows「添加打印机」和 Android
        // 默认打印服务读的正是系统那份，直接判定服务不可用。
        // 这三个键没有任何功能作用，删掉换回安全余量。
        "adminurl" to "http://$ip:$port/",
        "priority" to "0",
        "pdl" to if (gateway) pdlPassthrough() else pdl(),
        "URF" to urfString(),
        "kind" to "document",
        "Color" to "F",
        "Duplex" to "T",
        // 现在可以写 T：IPP 的 `copies-supported` 已开到 1..99，份数由 PCL 的
        // `ESC&l#X` 让打印机自己重复整个作业（数据量不随份数增长）。
        //
        // 以前必须是 F：那时 IPP 只声明 1..1，而 TXT 说 Copies=T —— 客户端会当成
        // 自述矛盾，面板里给了份数选择器、选 2 份却提交不出去。
        // **TXT 与 IPP 必须同源**，这条和 URF/Duplex 是同一类约束。
        "Copies" to "T",
        // 这三个是 CUPS/Bonjour Printing 规范里的键，标准的 AirPrint 打印机都会带。
        // `printer-state=3`（idle）让客户端在**发现阶段**就知道这台机器可用。
        //
        // 这里**故意没有 `air` 键**。曾经写过 `air=none`，后来改成 `air=print` ——
        // 两次都不对：Bonjour Printing 规范里没有这个键，真实可用的基线记录也没有。
        // 自行添加非规范键会让客户端比对能力表时出现未知项。
        "printer-state" to "3",
        // `printer-type` 是 CUPS 的位域（cups/cups.h）。原先照参考实现写了 `1`，
        // 但按 CUPS 的定义 `1` 是 `CUPS_PRINTER_CLASS` —— 等于宣称「我是一个
        // 打印机类（class）」，语义完全不对，`LOCAL` 其实是 `0x0000`。
        // 这台机器的正确取值是 4|8|32 = 44：
        //   CUPS_PRINTER_DISCOVERED 0x04  被局域网发现的
        //   CUPS_PRINTER_BW          0x08  黑白
        //   CUPS_PRINTER_DUPLEX      0x20  支持双面
        "printer-type" to "44",
        "PaperMax" to "legal-A4",
        "Scan" to "F",
        "Fax" to "F",
        // **必须写裸 UUID，不能带 `urn:uuid:` 前缀。**
        //
        // 这一条踩过真实的坑，症状是「Android 能发现、但一读能力就丢弃，打印机
        // 加不进去」。Android 的 NsdServiceInfo 拿到 TXT `UUID` 之后，自己会补
        // 一次前缀再和 IPP 的 `printer-uuid` 比对：
        //
        //   TXT 写 `urn:uuid:470b...` → Android 拼成 `urn:uuid:urn:uuid:470b...`
        //   IPP 回 `urn:uuid:470b...` → 比对失败
        //   → CapabilitiesCache: UUID mismatch ... rejecting capabilities
        //
        // 表现就是打印机在「添加打印机」列表里闪一下又没了，一个作业都提交不出去。
        // 写裸 UUID 时 Android 补完前缀正好等于 IPP 的值，两边对上。
        // 顺带对照：局域网里那台 CUPS 的 `_ipp._tcp` 记录压根没有 `UUID` 键，
        // 一直是能被 Android 添加的 —— 这个键是可选增强，不是必需品。
        "UUID" to printerUuid(context),
        )

        // 通用直通产不出 URF 光栅。留着 `URF=` 会让 iOS 把它当成 AirPrint 打印机
        // 列出来，用户点了却什么也打不出来 —— 与其给一个点不动的入口，不如让它
        // 不在 iOS 列表里出现，改用 Windows/macOS + 厂商驱动这条真正能走的路。
        if (gateway) txt.remove("URF")
        trimToNsdLimit(txt)
        return txt
    }

    /**
     * TXT 记录编码后的字节数（`len(1) + "key=value"`，每项一条）。
     *
     * 按 UTF-8 算：型号名里的中文（「系列」）是 6 字节而不是 2 字符，
     * 按字符数估算会低估三分之一，正好把余量吃光。
     */
    fun txtBytes(map: Map<String, String>): Int =
        map.entries.sumOf { (k, v) ->
            1 + k.toByteArray(Charsets.UTF_8).size + 1 + v.toByteArray(Charsets.UTF_8).size
        }

    /**
     * `NsdServiceInfo` 的 TXT 总长硬上限是 **400 字节**，超过之后系统**静默截断**，
     * 不抛异常也不报错。
     *
     * 这条比它看起来重要得多：系统 mdnsd 广播的那一份，正是 Windows「添加打印机」
     * 和 Android 默认打印服务读的东西；而 iPhone 读的是我们自己发的完整 TXT。
     * 所以一旦超限，症状是**iOS 正常、Windows 和 Android 搜不到**，而且没有任何
     * 一处日志会说"我截断了"。之前靠"删掉 note / usb_MFG / usb_MDL 把长度压到
     * 374"顶着，但型号名长度是不可控的（`Brother DCP-7080D` 比 `Brother HL-1110
     * 系列` 长，贴牌名更长），一次换机型就可能重新越线。所以改成每次注册都算。
     *
     * 裁剪顺序按「删了最不心疼」排：先纯展示键，再冗余的能力键，最后才动
     * `URF` / `pdl` / `rp` / `ty` 这些删了就会让打印机从列表里消失的键。
     */
    private val TRIM_ORDER = listOf(
        "PaperMax",      // 展示用，客户端不拿它决策
        "kind",          // IPP 侧有 printer-kind，重复
        "Scan", "Fax",   // 本机没有这两个功能，本来只是明示
        "adminurl",      // 只影响"更多信息"按钮
        "priority", "qtotal", // 单队列固定值
        "printer-type",  // CUPS 位域，非 AirPrint 必需
        "Copies",        // 份数能力，IPP 侧还有 copies-supported
    )

    /** 留 20 字节余量：不同实现对多字节字符的计法有出入。 */
    private const val TXT_LIMIT = 380

    private fun trimToNsdLimit(txt: LinkedHashMap<String, String>) {
        for (k in TRIM_ORDER) {
            if (txtBytes(txt) <= TXT_LIMIT) return
            txt.remove(k)
        }
        // 兜底：只剩型号名还可能超长，按剩余空间截断，至少保住 `URF`/`pdl`/`rp`。
        val ty = txt["ty"] ?: return
        val over = txtBytes(txt) - TXT_LIMIT
        if (over <= 0) return
        val keep = (ty.toByteArray(Charsets.UTF_8).size - over - 1).coerceAtLeast(1)
        // 按字节截可能切在 UTF-8 多字节序列中间，解码器会吐出 U+FFFD；一并去掉，
        // 宁可少一个字，也不要在打印机名字里留一串"�"。
        txt["ty"] = String(ty.toByteArray(Charsets.UTF_8), 0, keep, Charsets.UTF_8)
            .trimEnd('\uFFFD', ' ')
    }

    private fun androidId(context: Context): String =
        runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull() ?: "wprint"
}
