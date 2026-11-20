package com.cawzen.printserver.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cawzen.printserver.PrintOptions
import com.cawzen.printserver.usb.PrinterModel

/**
 * 设置页。
 *
 * 「安全」这一栏刻意不叫「密码」：IPP / AirPrint 协议里没有密码字段，
 * 做了也只能做在自家 App 上，AirPrint 客户端根本不会送。真正的控制手段是
 * IP 白名单（[AppSettings.allowNewDevices]），所以这里写明它、也只提供它。
 */
@Composable
fun SettingsScreen(
    rt: Runtime,
    ctl: UiController,
    options: PrintOptions,
    onOptions: (PrintOptions) -> Unit,
    onRerunWizard: () -> Unit,
) {
    val context = LocalContext.current
    val cfg = ctl.cfg
    val pkg = remember {
        runCatching {
            val i = context.packageManager.getPackageInfo(context.packageName, 0)
            "${i.versionName} (${i.longVersionCode})"
        }.getOrDefault("—")
    }
    val batteryOk = remember(rt.status) { ctl.isIgnoringBattery() }
    val notifyOk = remember(rt.status) { ctl.notificationsEnabled() }
    var pickingModel by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("设置", style = MaterialTheme.typography.headlineMedium, color = cp.onBg)
            Text("后台、通知、安全与兼容性", style = MaterialTheme.typography.bodyMedium, color = cp.onBgDim)
        }

        // ── 后台与通知 ──────────────────────────────────────────────────────
        SectionTitle("后台与通知")
        Panel {
            SwitchRow(
                title = "开机自动恢复共享",
                subtitle = "上次是开着共享关机的，开机后自动起来",
                checked = cfg.autoStartOnBoot,
                onChange = { v -> ctl.updateCfg { it.copy(autoStartOnBoot = v) } },
            )
            HLine()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("电池优化白名单", style = MaterialTheme.typography.bodyLarge, color = cp.onPanel)
                    Text(
                        if (batteryOk) "已在白名单，后台不易被冻结"
                        else "未加入：锁屏一段时间后共享可能被系统掐断",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (batteryOk) cp.onPanelDim else cp.warn,
                    )
                }
                TextAction(if (batteryOk) "查看" else "去设置") {
                    ctl.requestIgnoreBattery()
                }
            }
            HLine()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("通知权限", style = MaterialTheme.typography.bodyLarge, color = cp.onPanel)
                    Text(
                        if (notifyOk) "已允许，常驻通知可见" else "已关闭：服务仍会运行，但通知栏看不到",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (notifyOk) cp.onPanelDim else cp.warn,
                    )
                }
                TextAction(if (notifyOk) "系统设置" else "允许") {
                    if (notifyOk) ctl.openNotificationSettings() else ctl.requestNotifications()
                }
            }
            HLine()
            SwitchRow(
                title = "作业结果通知",
                subtitle = "打完 / 失败时补一条通知",
                checked = cfg.notifyJobs,
                onChange = { v -> ctl.updateCfg { it.copy(notifyJobs = v) } },
            )
            Text(
                "小米 / MIUI 还需要在「应用管理 → 省电策略」里选「无限制」，" +
                    "否则锁屏后前台服务也会被清掉。",
                style = MaterialTheme.typography.bodySmall,
                color = cp.onPanelDim,
            )
        }

        // ── 安全 ────────────────────────────────────────────────────────────
        SectionTitle("安全")
        Panel {
            SwitchRow(
                title = "允许新设备接入",
                subtitle = "关闭后只放行下面已授权的设备",
                checked = cfg.allowNewDevices,
                onChange = { v -> ctl.updateCfg { it.copy(allowNewDevices = v) } },
            )
            HLine()
            if (cfg.approvedClients.isEmpty()) {
                Text(
                    "还没有授权设备。关掉「允许新设备」后，第一个来打印的设备会出现在" +
                        "「共享」页的已连接列表里，点「允许」即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = cp.onPanelDim,
                )
            } else {
                cfg.approvedClients.forEach { ip ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Mono(ip, size = 12.sp, color = cp.onPanel, modifier = Modifier.weight(1f))
                        TextAction("移除") { ctl.revokeClient(ip) }
                    }
                }
            }
            HLine()
            KeyValueRow("共享密码", "不支持（协议无此字段）", mono = false, valueColor = cp.onPanelDim)
            Text(
                "为什么没有密码：AirPrint / IPP 协议本身不带认证字段，客户端不会送密码。" +
                    "在本机加一道密码只会让 Windows 与 iPhone 都连不上，所以用 IP 白名单代替。",
                style = MaterialTheme.typography.bodySmall,
                color = cp.onPanelDim,
            )
        }

        // ── 打印默认值 ──────────────────────────────────────────────────────
        SectionTitle("打印默认值")
        Panel {
            Text(
                "画质",
                style = MaterialTheme.typography.bodyLarge,
                color = cp.onPanel,
            )
            Segmented(
                options = listOf("300 dpi 快速", "600 dpi 细腻"),
                selected = if (options.dpi == 600) 1 else 0,
                onSelect = { i ->
                    val dpi = if (i == 1) 600 else 300
                    PrintOptions.saveDpi(context, dpi)
                    onOptions(options.withDpi(dpi))
                },
            )
            Text(
                "只影响 PDF 渲染。PWG / URF 的分辨率跟文档页头走，客户端说了算。",
                style = MaterialTheme.typography.bodySmall,
                color = cp.onPanelDim,
            )
            HLine()
            Text("本机打印的装订方式", style = MaterialTheme.typography.bodyLarge, color = cp.onPanel)
            Segmented(
                options = listOf("单面", "双面"),
                selected = if (options.duplex) 1 else 0,
                onSelect = { i ->
                    val d = i == 1
                    PrintOptions.save(context, options.withDuplex(d))
                    onOptions(options.withDuplex(d))
                },
            )
            Text(
                "只用于「测试页」和手机系统打印。网络作业的单双面只信客户端自己的设置，" +
                    "否则 iPhone 上选了双面、这里停在单面，出来就是两张单面纸。",
                style = MaterialTheme.typography.bodySmall,
                color = cp.onPanelDim,
            )
        }

        // ── 协议 ────────────────────────────────────────────────────────────
        SectionTitle("协议")
        Panel {
            ChipGroup(
                options = listOf(
                    "AirPrint" to cfg.airprint,
                    "Mopria" to cfg.mopria,
                    "IPP" to true,
                    "RAW" to cfg.rawEnabled,
                ),
                onToggle = { i ->
                    when (i) {
                        0 -> ctl.updateCfg { it.copy(airprint = !it.airprint) }
                        1 -> ctl.updateCfg { it.copy(mopria = !it.mopria) }
                        2 -> ctl.say("IPP 是基础协议，关闭它整台打印机就不可用了")
                        3 -> ctl.updateCfg { it.copy(rawEnabled = !it.rawEnabled) }
                    }
                },
            )
            Mono(
                "IPP ${rt.status?.port ?: "—"} · RAW ${rt.status?.rawPort ?: 9101}" +
                    if (rt.status?.rawRunning == true) "（已开启）" else "（未开启）",
                size = 10.sp,
                color = cp.onPanelDim,
            )
            Text(
                "AirPrint 依赖 Mopria 的 _ipp._tcp 主广播：关掉 Mopria 时 AirPrint 会一起关，" +
                    "否则 iPhone 会看到一个解析不出地址的服务名。",
                style = MaterialTheme.typography.bodySmall,
                color = cp.onPanelDim,
            )
        }

        // ── 对外名称 ────────────────────────────────────────────────────────
        SectionTitle("对外名称")
        Panel {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "打印机名称",
                    style = MaterialTheme.typography.bodySmall,
                    color = cp.onPanelDim,
                )
                // 名字可能很长（「客厅的联想激光打印机」），标题字号会换行成一坨，
                // 用正文大字 + 允许换行，比 titleLarge 稳。
                Text(
                    cfg.effectivePrinterName(),
                    style = MaterialTheme.typography.titleMedium,
                    color = cp.onPanel,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Mono(
                        if (cfg.printerName.isBlank()) "默认名 · mDNS 实例名" else "自定义 · mDNS 实例名",
                        size = 10.sp,
                        color = cp.onPanelDim,
                    )
                    TextAction(if (cfg.printerName.isBlank()) "改名" else "修改") { renaming = true }
                }
            }
            Text(
                "iPhone / Windows / Android 的打印机列表里显示的就是它。" +
                    "改名会重发一次 mDNS 广播，已经添加过的设备可能需要删掉旧的再加一次。",
                style = MaterialTheme.typography.bodySmall,
                color = cp.onPanelDim,
            )
        }

        // ── 打印机型号 ──────────────────────────────────────────────────────
        SectionTitle("打印机型号")
        Panel {
            val picked = PrinterModel.byId(cfg.printerModelId)
            // 型号名长短差很多（「Brother DCP-1510 系列」到「联想 M7605D」），
            // 塞进 KeyValueRow 的值列会被挤成「（手动指 / 定）」。改成纵向：标签在上，
            // 型号独占一行，附注单独一行。
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "当前型号",
                    style = MaterialTheme.typography.bodySmall,
                    color = cp.onPanelDim,
                )
                Text(
                    when {
                        picked != null -> picked.name
                        rt.device.connected -> rt.device.name.ifBlank { "自动识别" }
                        else -> "未识别"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = if (picked != null) cp.onPanel else cp.onPanel,
                )
                Mono(
                    when {
                        picked != null -> "手动指定 · 自动识别已关闭"
                        rt.device.connected -> "按 VID/PID 自动识别"
                        else -> "接上 USB 打印机后自动判断"
                    },
                    size = 10.sp,
                    color = if (picked != null) cp.warn else cp.onPanelDim,
                )
            }
            Text(
                "两条路，按机芯选：\n" +
                    "· ${PrinterModel.BRLASER_COUNT} 款兄弟机芯可直接渲染（含 ${PrinterModel.ALIAS_COUNT} 款贴牌机：联想 / 富士胶片 / 东芝 / 柯美）。" +
                    "这些机器的粉盒能互换，说明机芯相同，编码完全一致。\n" +
                    "· 其它品牌（HP / 佳能 / 京瓷 / 理光 / 奔图…）选「通用 PCL / PostScript 打印机」，" +
                    "原样转发电脑上厂商驱动排好的数据 —— 代价是 iPhone 用不了。",
                style = MaterialTheme.typography.bodySmall,
                color = cp.onPanelDim,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietAction(
                    if (picked == null) "选择机型" else "更换机型",
                    { pickingModel = true },
                    Modifier.weight(1f),
                )
                if (picked != null) {
                    QuietAction("恢复自动识别", { ctl.setPrinterModel("") }, Modifier.weight(1f))
                }
            }
            Text(
                "型号只影响识别结果与显示名，不改变打印参数 —— 分辨率、单双面、纸张都按作业设置走。",
                style = MaterialTheme.typography.bodySmall,
                color = cp.onPanelDim,
            )
        }

        // ── 兼容性 ──────────────────────────────────────────────────────────
        SectionTitle("兼容性")
        Panel {
            KeyValueRow("识别方式", "手动指定 → VID/PID → 厂商+接口")
            KeyValueRow("支持厂商", "Brother 0x04F9 / 联想 0x17EF")
            KeyValueRow("当前设备", rt.device.name.ifBlank { "未连接" }, mono = false)
            HLine()
            Text(
                "已知限制",
                style = MaterialTheme.typography.bodyLarge,
                color = cp.onPanel,
            )
            listOf(
                "IPP 端口 8631：非 Root 应用绑不了 631。Windows / macOS 手填端口即可。",
                "RAW 端口 9101：9100 同样是系统保留端口，Windows 里端口号可以随便填。",
                "读不到粉量、页数、面板菜单，也不支持清洗/校准 —— 硬件接口没有这些通道。",
                "渲染模式只覆盖黑白激光的兄弟同构机芯（含贴牌）；其它机芯必须走通用直通。",
            ).forEach {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dot(cp.idle, 5.dp)
                    Text(it, style = MaterialTheme.typography.bodySmall, color = cp.onPanelDim)
                }
            }
        }

        // ── 关于 ────────────────────────────────────────────────────────────
        SectionTitle("关于")
        Panel {
            // 这里原来挂了一个「待机 / 共享中」胶囊，和版本号并排 —— 语义错位：
            // 关于卡讲的是「这是什么版本」，运行状态属于状态页。
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Cprinter", style = MaterialTheme.typography.titleLarge, color = cp.onPanel)
                Mono("v$pkg · com.cawzen.directprint", size = 10.sp, color = cp.onPanelDim)
            }
            Text(
                "把 USB 有线打印机改造成局域网无线打印机：手机做网关，不 Root、不装驱动。" +
                    "渲染核心是 brlaser（GPL-3.0），因此本应用同样以 GPL-3.0 分发。",
                style = MaterialTheme.typography.bodySmall,
                color = cp.onPanelDim,
            )
            HLine()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietAction("重新运行向导", onRerunWizard, Modifier.weight(1f))
                QuietAction("清空历史", { ctl.clearHistory() }, Modifier.weight(1f))
            }
            TextAction("应用详情（权限 / 存储 / 卸载）") { ctl.openAppDetails() }
        }

        Spacer(Modifier.height(20.dp))
    }

    if (pickingModel) {
        ModelPickerDialog(
            current = cfg.printerModelId,
            onPick = { id ->
                pickingModel = false
                ctl.setPrinterModel(id)
            },
            onDismiss = { pickingModel = false },
        )
    }

    if (renaming) {
        RenameDialog(
            current = cfg.effectivePrinterName(),
            onSave = { name ->
                renaming = false
                ctl.setPrinterName(name)
            },
            onDismiss = { renaming = false },
        )
    }
}
