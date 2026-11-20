package com.cawzen.printserver.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cawzen.printserver.net.NetInfo

/**
 * 共享页：把「怎么连」讲清楚。
 *
 * 这一页的核心不是开关，而是**地址**。用户真正需要抄走的是三样东西：
 * 二维码（一键添加）、`IP:8631`（IPP）、`IP:9101`（Windows 标准 TCP/IP 端口）。
 * 所以二维码和地址放在最上面，设备列表与安全设置放下面。
 */
@Composable
fun ShareScreen(rt: Runtime, ctl: UiController) {
    val context = LocalContext.current
    val st = rt.status
    val running = rt.running
    val cfg = ctl.cfg
    val net = remember(rt.status?.ippUri) { NetInfo.wifi(context) }
    val detectedHotspot = net?.name?.let { isHotspotIface(it) } == true
    var hotspotMode by remember(detectedHotspot) { mutableStateOf(detectedHotspot) }

    val ip = net?.ip ?: "—"
    val ippUri = st?.ippUri ?: "—"
    val httpUri = st?.httpUri ?: "—"
    val rawPort = st?.rawPort ?: 9101

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("无线共享", style = MaterialTheme.typography.headlineMedium, color = cp.onBg)
                Text(
                    if (running) "其他设备现在就可以打印" else "共享未启动",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cp.onBgDim,
                )
            }
            Pill(if (running) "运行中" else "已停止", if (running) Tone.Ok else Tone.Idle)
        }

        // ── 开关 ────────────────────────────────────────────────────────────
        Panel {
            // 不再在右侧放一个孤立的状态圆点：标题行已经有「运行中 / 已停止」胶囊，
            // 同一个事实画两遍只会让人去找它俩的差别。
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (running) "共享已开启" else "共享已关闭",
                    style = MaterialTheme.typography.titleMedium,
                    color = cp.onPanel,
                )
                Mono(
                    "IPP ${st?.port ?: "—"} · 已连接 ${rt.clients.count { it.fresh && !it.blocked }} 台设备",
                    size = 11.sp,
                    color = cp.onPanelDim,
                )
            }
            if (running) {
                QuietAction("暂停共享", { ctl.stopShare() }, Modifier.fillMaxWidth(), tone = Tone.Bad)
            } else {
                PrimaryAction(
                    "启动无线共享",
                    { ctl.startShare() },
                    Modifier.fillMaxWidth(),
                    enabled = rt.device.connected && rt.device.permission && ctl.busy == null,
                    secondary = if (!rt.device.connected) "先连接打印机" else null,
                )
            }
        }

        // ── 模式 ────────────────────────────────────────────────────────────
        SectionTitle("接入方式")
        Panel {
            Segmented(
                options = listOf("家庭 Wi-Fi", "手机热点"),
                selected = if (hotspotMode) 1 else 0,
                onSelect = { hotspotMode = it == 1 },
            )
            Text(
                if (hotspotMode) {
                    "手机开热点，其它设备连这个热点后打印。没有路由器时用；" +
                        "注意热点模式下手机通常会被分配 192.168.43.1，且部分机型热点会关闭 Wi-Fi 组播。"
                } else {
                    "手机和打印机在同一台路由器下，手机把 USB 打印机的作业转发到局域网。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = cp.onPanelDim,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietAction(
                    if (hotspotMode) "打开热点设置" else "打开 Wi-Fi 设置",
                    { if (hotspotMode) ctl.openHotspotSettings() else ctl.openWifiSettings() },
                    Modifier.weight(1f),
                )
                QuietAction("重启共享", { ctl.restartShare() }, Modifier.weight(1f))
            }
            HLine()
            KeyValueRow("当前网络", net?.name ?: "未连接")
            KeyValueRow("本机地址", ip)
        }

        // ── 二维码与地址 ────────────────────────────────────────────────────
        SectionTitle("扫码添加")
        Panel {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                QrCanvas(
                    text = if (running) ippUri else "http://$ip:${st?.port ?: 8631}/",
                    modifier = Modifier.size(208.dp),
                )
                Text(
                    if (running) "用 iPhone / iPad / Mac 相机扫码，自动添加这台打印机"
                    else "启动共享后生成可扫描的地址",
                    style = MaterialTheme.typography.bodySmall,
                    color = cp.onPanelDim,
                )
            }
            HLine()
            AddressRow("IPP 地址", ippUri, "IPP") { ctl.copy("IPP 地址", ippUri) }
            AddressRow("状态页", httpUri, "HTTP") { ctl.copy("状态页", httpUri) }
            AddressRow(
                "RAW 端口",
                if (st?.rawRunning == true) "$ip:${st.rawPort}" else "$ip:$rawPort（未开启）",
                "RAW",
            ) { ctl.copy("RAW 地址", "$ip:$rawPort") }
            HLine()
            // 这一段是「搜不到」时最需要的东西：三端的入口位置完全不同，而系统的
            // 「添加打印机」在 Windows 上并不列出 mDNS 发现的 IPP 设备（只认 WSD），
            // 所以必须给出**手动填 URL** 这条确定能走的路，否则用户只会以为坏了。
            Text(
                "各端怎么加",
                style = MaterialTheme.typography.labelSmall,
                color = cp.onPanelDim,
            )
            Text(
                "iPhone / iPad：打印面板里直接选「${cfg.effectivePrinterName()}」，不用填地址。\n" +
                    "Windows：添加打印机 →「我需要的打印机不在列表中」→ 按名称输入上面的 IPP 地址。\n" +
                    "Android：设置 → 打印 → 默认打印服务 → 添加打印机（手机需与本机同一网段）。",
                style = MaterialTheme.typography.bodySmall,
                color = cp.onPanel,
            )
            Text(
                "Windows 的「添加设备」只靠 WSD 发现，看不到 mDNS 广播的 IPP 打印机 —— " +
                    "这不是本机没广播，是 Windows 那一侧的产品行为。按名称填 URL 一次即可，之后会记住。",
                style = MaterialTheme.typography.bodySmall,
                color = cp.onPanelDim,
            )
            Text(
                "RAW 端口只给装了厂商驱动的电脑用（标准 TCP/IP 端口）。9100 是系统保留端口，" +
                    "非 Root 应用绑不上，所以用 9101 —— 端口号可以随便填。",
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
                    "Mopria" to cfg.effectiveMopria(),
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
            Text(
                when {
                    cfg.airprint && cfg.effectiveMopria() ->
                        "iPhone / iPad 走 AirPrint（带 _universal 子类型），Windows 与 Android 走 _ipp._tcp。"
                    cfg.effectiveMopria() ->
                        "只广播 _ipp._tcp：Windows / Android 可见，iOS 列表里不会出现。"
                    else -> "两个发现协议都关了，只能手动填 IPP 地址。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = cp.onPanelDim,
            )
            if (st?.mdnsError?.isNotBlank() == true) {
                Mono("mDNS: ${st.mdnsError}", size = 10.sp, color = cp.warn)
            }
        }

        // ── 已连接设备 ──────────────────────────────────────────────────────
        SectionTitle("已连接设备") {
            Mono("${rt.clients.size}", size = 11.sp, color = cp.onBgDim)
        }
        Panel {
            if (rt.clients.isEmpty()) {
                Text(
                    "还没有设备访问过。设备第一次打印时会出现在这里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = cp.onPanelDim,
                )
            } else {
                rt.clients.forEach { c ->
                    ClientLine(c, rt.activeJob?.client == c.ip) { ctl.approveClient(c.ip) }
                    if (c != rt.clients.last()) HLine()
                }
            }
            HLine()
            SwitchRow(
                title = "允许新设备",
                subtitle = if (cfg.allowNewDevices) "任何同网设备都能接入（局域网内默认信任）"
                else "只放行已授权的 IP，陌生设备会被拒绝",
                checked = cfg.allowNewDevices,
                onChange = { v -> ctl.updateCfg { it.copy(allowNewDevices = v) } },
            )
            Text(
                "AirPrint / IPP 协议本身没有密码字段，所以这里用「IP 白名单」而不是密码：" +
                    "关掉「允许新设备」后，只有你点过「允许」的设备能打印。",
                style = MaterialTheme.typography.bodySmall,
                color = cp.onPanelDim,
            )
        }

        // ── 提醒 ────────────────────────────────────────────────────────────
        Panel(padding = 14.dp) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dot(cp.warn, 7.dp)
                Text(
                    "省电模式、后台清理、Wi-Fi 休眠都可能让共享中断。若设备列表突然空了，" +
                        "先看通知栏的常驻通知是否还在 —— 它没了通常意味着服务被系统杀掉了。",
                    style = MaterialTheme.typography.bodySmall,
                    color = cp.onPanelDim,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ClientLine(c: ClientRow, printing: Boolean, onAllow: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(c.label, style = MaterialTheme.typography.bodyLarge, color = cp.onPanel)
                when {
                    printing -> Pill("打印中", Tone.Ok)
                    c.blocked -> Pill("待允许", Tone.Warn)
                }
            }
            Mono(
                "${c.ip} · ${if (c.jobs > 0) "${c.jobs} 个作业" else "仅查询"} · ${fmtRelative(c.lastSeen)}",
                size = 10.sp,
                color = cp.onPanelDim,
            )
        }
        if (c.blocked) {
            TextAction("允许", onClick = onAllow)
        }
    }
}

@Composable
private fun AddressRow(label: String, value: String, tag: String, onCopy: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            tag,
            style = MaterialTheme.typography.labelMedium,
            color = cp.onPanelDim,
            modifier = Modifier.width(48.dp),
        )
        Mono(value, size = 11.sp, color = cp.onPanel, modifier = Modifier.weight(1f))
        TextAction("复制", onClick = onCopy)
    }
}

/** 热点接口的命名各家 ROM 都不一样，只能按前缀猜。猜错只影响提示文案。 */
private fun isHotspotIface(name: String): Boolean {
    val n = name.lowercase()
    return n.startsWith("ap") || n == "wlan1" || n.startsWith("swlan") || n.startsWith("softap")
}
