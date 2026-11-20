package com.cawzen.printserver

import android.content.Context

/**
 * App 的「意图」设置（与打印质量参数 [PrintOptions] 分开存）。
 *
 * 为什么单独一份：这些值影响的是**服务对外长什么样**（广播哪些协议、谁能接入、
 * 开机要不要自己起来），而 [PrintOptions] 影响的是**一页纸怎么渲染**。两者的
 * 生命周期、出错后果、修改频率都不一样，混在一个 data class 里会让「改个画质」
 * 也去碰协议开关，谁都不敢动。
 *
 * 所有字段都持久化。`shareEnabled` 存的是**用户意图**而不是服务当前状态 ——
 * 进程被系统杀掉后要能自己恢复，靠的就是这个意图位。
 */
data class AppSettings(
    /** 首次使用向导是否已走完。false 时主界面直接进向导。 */
    val wizardDone: Boolean = false,

    /** 用户希望共享保持运行。开机自启/进程重启后据此恢复。 */
    val shareEnabled: Boolean = false,

    /** 开机自动恢复共享（依赖 [shareEnabled]）。 */
    val autoStartOnBoot: Boolean = true,

    /** 广播 `_universal._sub._ipp._tcp`（iOS / macOS 的 AirPrint 入口）。 */
    val airprint: Boolean = true,

    /** 广播 `_ipp._tcp`（Windows / Android/Mopria 的入口）。AirPrint 依赖它。 */
    val mopria: Boolean = true,

    /** 打开 RAW 端口（Windows「标准 TCP/IP 端口」直连，默认 9101）。 */
    val rawEnabled: Boolean = false,

    /** 是否允许陌生 IP 接入。false 时只放行 [approvedClients]。 */
    val allowNewDevices: Boolean = true,

    /** 作业完成/失败时是否在通知栏提示。 */
    val notifyJobs: Boolean = true,

    /** 上次记录「更换粉盒」的时间（0 = 从未记录）。粉量不可回读，只能记事件。 */
    val tonerChangedAt: Long = 0L,

    /**
     * 手动指定的打印机型号（`PrinterModel.CATALOG` 里的 id，空串 = 自动识别）。
     *
     * 存在的意义：VID/PID 表不可能覆盖所有 OEM 贴牌机。自动识别失败时，用户可以从
     * brlaser 支持列表里挑一个，App 就按这个型号接入 —— 这与 brlaser 官方
     * 「不在列表里就随便选一个 brlaser 条目试试」的建议一致。
     */
    val printerModelId: String = "",

    /** 已授权的客户端 IP（[allowNewDevices] 关闭时的白名单）。 */
    val approvedClients: Set<String> = emptySet(),

    /**
     * 对外显示的打印机名（mDNS 服务实例名 + IPP `printer-name`）。
     *
     * 存的是**用户输入原文**（已规整），空串表示用默认的 [DEFAULT_PRINTER_NAME]。
     * 不把默认值直接写进字段，是为了「恢复默认」之后还能跟着以后的默认名走，
     * 而不是把一个曾经叫过 Cprinter 的字符串永久钉在配置里。
     */
    val printerName: String = "",
) {

    /** AirPrint 必须有 `_ipp._tcp` 主广播，否则 iOS 拿到子类型也解析不出地址。 */
    fun effectiveMopria(): Boolean = mopria || airprint

    /**
     * 实际对外广播的名字。
     *
     * 兜底截断不是给界面用的（对话框会拦住），而是防止手改 SharedPreferences、
     * 或以后别处写入超长值时，mDNS 注册直接失败（DNS 标签硬上限 63 字节，
     * 超了 `NsdManager` 报 FAILURE_INTERNAL_ERROR，表现是"一个客户端都搜不到"）。
     */
    fun effectivePrinterName(): String {
        val n = normalizePrinterName(printerName).ifBlank { DEFAULT_PRINTER_NAME }
        return truncateUtf8(n, MAX_PRINTER_NAME_BYTES)
    }

    companion object {
        private const val PREF = "cprinter_settings"

        private const val K_WIZARD = "wizard_done"
        private const val K_SHARE = "share_enabled"
        private const val K_BOOT = "auto_start_boot"
        private const val K_AIRPRINT = "airprint"
        private const val K_MOPRIA = "mopria"
        private const val K_RAW = "raw_enabled"
        private const val K_ALLOW_NEW = "allow_new_devices"
        private const val K_NOTIFY = "notify_jobs"
        private const val K_TONER = "toner_changed_at"
        private const val K_APPROVED = "approved_clients"
        private const val K_MODEL = "printer_model_id"
        private const val K_NAME = "printer_name"

        /** 默认对外名称。改名功能清空后回到这里。 */
        const val DEFAULT_PRINTER_NAME = "Cprinter"

        /**
         * mDNS 服务实例名是一个 DNS 标签，**上限 63 字节**（不是 63 个字符）。
         *
         * 中文一个字 3 字节，所以纯中文名最多 21 个字。超限时 `NsdManager`
         * 只回一个 FAILURE_INTERNAL_ERROR，不说原因 —— 界面上必须提前拦住，
         * 否则用户改完名会以为"改名把打印机改死了"。
         */
        const val MAX_PRINTER_NAME_BYTES = 63

        fun nameBytes(s: String): Int = s.toByteArray(Charsets.UTF_8).size

        /**
         * 规整用户输入的名字。
         *
         * - 去首尾空白，内部连续空白压成一个空格（名字是给人看的，不需要对齐）；
         * - 去掉控制字符（粘贴进来的换行、制表符会让 DNS 标签变成一堆不可见字节）；
         * - `.` `\` `/` 换成 `-`：实例名会被拼成「名字._ipp._tcp.local」，
         *   名字里带点会被当成**标签分隔符**，系统 mdnsd 会转义成 `\.`，
         *   而 iOS 走的是我们自己发的报文，两边写法不一致 → 同一台机器出现两个名字，
         *   或者干脆解析不到。换成短横线最省事，也符合人的直觉。
         *
         * 不在这里截断长度：截断要由调用方决定（界面报错 vs 兜底），
         * 悄悄改掉用户输入的名字比报错更让人困惑。
         */
        fun normalizePrinterName(raw: String): String {
            val sb = StringBuilder()
            var lastSpace = false
            for (ch in raw) {
                val c = when (ch) {
                    '.', '\\', '/' -> '-'
                    else -> ch
                }
                if (c.isISOControl()) continue
                if (c == ' ') {
                    if (!lastSpace && sb.isNotEmpty()) sb.append(' ')
                    lastSpace = true
                } else {
                    sb.append(c)
                    lastSpace = false
                }
            }
            return sb.toString().trim()
        }

        /**
         * 按 UTF-8 字节数截断，且不切断多字节字符。
         *
         * 直接 `toByteArray().copyOf(n)` 可能切在汉字中间，解出来是 U+FFFD，
         * 客户端列表里就会出现一串「�」。
         */
        fun truncateUtf8(s: String, maxBytes: Int): String {
            if (nameBytes(s) <= maxBytes) return s
            var end = s.length
            while (end > 0 && nameBytes(s.substring(0, end)) > maxBytes) end--
            return s.substring(0, end).trim()
        }

        fun load(context: Context): AppSettings {
            val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            return AppSettings(
                wizardDone = sp.getBoolean(K_WIZARD, false),
                shareEnabled = sp.getBoolean(K_SHARE, false),
                autoStartOnBoot = sp.getBoolean(K_BOOT, true),
                airprint = sp.getBoolean(K_AIRPRINT, true),
                mopria = sp.getBoolean(K_MOPRIA, true),
                rawEnabled = sp.getBoolean(K_RAW, false),
                allowNewDevices = sp.getBoolean(K_ALLOW_NEW, true),
                notifyJobs = sp.getBoolean(K_NOTIFY, true),
                tonerChangedAt = sp.getLong(K_TONER, 0L),
                approvedClients = sp.getStringSet(K_APPROVED, emptySet())?.toSet() ?: emptySet(),
                printerModelId = sp.getString(K_MODEL, "") ?: "",
                printerName = sp.getString(K_NAME, "") ?: "",
            )
        }

        fun save(context: Context, s: AppSettings) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean(K_WIZARD, s.wizardDone)
                .putBoolean(K_SHARE, s.shareEnabled)
                .putBoolean(K_BOOT, s.autoStartOnBoot)
                .putBoolean(K_AIRPRINT, s.airprint)
                .putBoolean(K_MOPRIA, s.mopria)
                .putBoolean(K_RAW, s.rawEnabled)
                .putBoolean(K_ALLOW_NEW, s.allowNewDevices)
                .putBoolean(K_NOTIFY, s.notifyJobs)
                .putLong(K_TONER, s.tonerChangedAt)
                .putStringSet(K_APPROVED, s.approvedClients)
                .putString(K_MODEL, s.printerModelId)
                .putString(K_NAME, s.printerName)
                .apply()
        }

        /**
         * 单字段更新。
         *
         * 用「读 — 改 — 写」整份而不是逐个 key 写：整份只有十来个字段，写错的
         * 风险远小于漏写一个 key；而漏写造成的表现是「重启后又变回去了」，很难查。
         */
        fun update(context: Context, block: (AppSettings) -> AppSettings): AppSettings {
            val next = block(load(context))
            save(context, next)
            return next
        }
    }
}
