package com.cawzen.printserver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cawzen.printserver.net.NetInfo
import androidx.compose.ui.platform.LocalContext

/**
 * 首次使用向导：连接 → 识别 → 网络 → 共享 → 完成。
 *
 * 每一步只做一件事，且**每一步的「下一步」都有真实前置条件**：没检测到打印机
 * 就进不了识别，没启动共享就进不了完成。失败时不只报错，还给下一步动作
 * （重新检测 / 去系统设置 / 授权）。
 */
@Composable
fun Wizard(rt: Runtime, ctl: UiController, onFinish: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(0) }
    var testMsg by remember { mutableStateOf<String?>(null) }
    val dev = rt.device
    val st = rt.status
    val net = remember(rt.status?.ippUri) { NetInfo.wifi(context) }
    val ip = net?.ip ?: "—"
    val cfg = ctl.cfg
    val steps = listOf("连接", "识别", "网络", "共享", "完成")

    val canNext = when (step) {
        0 -> dev.connected && dev.permission
        1 -> dev.connected
        2 -> ip != "127.0.0.1" && ip != "—"
        3 -> rt.running
        else -> true
    }
    val blockReason = when {
        canNext -> null
        step == 0 && !dev.connected -> "先用 USB OTG 线把打印机接到手机上"
        step == 0 -> "点「授权 USB 访问」后继续"
        step == 1 -> "打印机不在了，回到上一步重新检测"
        step == 2 -> "手机还没连上局域网（Wi-Fi）"
        step == 3 -> "点「启动共享」后再继续"
        else -> null
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(cp.bg)
            .padding(horizontal = 20.dp),
    ) {
        // ── 步骤条 ──────────────────────────────────────────────────────────
        Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("初次设置", style = MaterialTheme.typography.headlineMedium, color = cp.onBg)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.forEachIndexed { i, label ->
                    val on = i <= step
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (on) cp.onBg else cp.onBgDim.copy(alpha = 0.35f)),
                        )
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (i == step) cp.onBg else cp.onBgDim,
                            fontWeight = if (i == step) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (step) {
                0 -> {
                    StepTitle("把打印机接到手机", "这一步只做一件事：让 Android 认到 USB 打印机。")
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(if (dev.connected) cp.ok else cp.idle, 9.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (dev.connected) "已检测到打印机" else "等待打印机接入",
                                style = MaterialTheme.typography.titleMedium,
                                color = cp.onPanel,
                                modifier = Modifier.weight(1f),
                            )
                            if (dev.connected) Pill(dev.name, Tone.Ok)
                        }
                        if (dev.connected) {
                            Mono(
                                "VID=0x%04X PID=0x%04X · %s".format(
                                    dev.vid, dev.pid,
                                    if (dev.permission) "已授权" else "未授权",
                                ),
                                size = 10.sp,
                                color = cp.onPanelDim,
                            )
                            if (!dev.permission) {
                                PrimaryAction("授权 USB 访问", { ctl.requestUsbPermission() }, Modifier.fillMaxWidth())
                            }
                        } else {
                            Text(
                                "1. 打印机开机并自检完成\n2. 用 OTG 线（不是纯充电线）接到手机\n3. 弹窗里选「允许」",
                                style = MaterialTheme.typography.bodySmall,
                                color = cp.onPanelDim,
                            )
                            if (dev.otherDevices > 0) {
                                Mono(
                                    "检测到 ${dev.otherDevices} 个其它 USB 设备，都不是打印机",
                                    size = 10.sp,
                                    color = cp.warn,
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextAction("重新检测") { ctl.say("已重新扫描 USB 设备") }
                                TextAction("授权 USB 访问") { ctl.requestUsbPermission() }
                            }
                        }
                        HLine()
                        Text(
                            "${com.cawzen.printserver.usb.PrinterModel.BRLASER_COUNT} 款兄弟机芯可直接渲染" +
                                "（含联想 / 富士胶片 / 东芝 / 柯美同机芯贴牌机），" +
                                "其它品牌可选「通用 PCL / PostScript」直通。" +
                                "识别不出来可以在「设置 → 打印机型号」里手动指定。" +
                                "拔插或重启手机后，USB 授权需要重新确认。",
                            style = MaterialTheme.typography.bodySmall,
                            color = cp.onPanelDim,
                        )
                    }
                    TextAction("找不到打印机？先跳过，进入界面看诊断") {
                        ctl.markWizardDone()
                        onFinish()
                    }
                }

                1 -> {
                    StepTitle("确认型号与能力", "识别决定用哪套 PCL 编码，以及界面能显示哪些状态。")
                    Panel {
                        PanelLabel("识别结果")
                        Text(
                            dev.name.ifBlank { "未知型号" },
                            style = MaterialTheme.typography.headlineMedium,
                            color = cp.onPanel,
                        )
                        Mono(
                            "VID 0x%04X · PID 0x%04X · %s".format(
                                dev.vid, dev.pid,
                                if (dev.exact) "精确匹配型号表" else "厂商级推断（brlaser 兼容）",
                            ),
                            size = 10.sp,
                            color = cp.onPanelDim,
                        )
                        if (!dev.exact) {
                            Text(
                                "这台机不在精确表里，按厂商 + 打印机类接口判定可尝试。打一张测试页就能确认。",
                                style = MaterialTheme.typography.bodySmall,
                                color = cp.warn,
                            )
                        }
                    }
                    Panel {
                        PanelLabel("能读到的")
                        Capability("端口状态位", "缺纸 / 故障 / 离线 —— USB 打印机类标准请求，1 字节")
                        Capability("作业结果", "页数、份数、失败原因（本 App 自己统计）")
                        HLine()
                        PanelLabel("读不到的")
                        Capability("粉量余量", "这台机没有耗材回读通道，界面不画假格数", warn = true)
                        Capability("计数器 / 面板菜单", "USB 侧没有对应命令", warn = true)
                    }
                }

                2 -> {
                    StepTitle("选择网络", "打印机通过手机转发，所以手机自己必须在局域网里。")
                    Panel {
                        KeyValueRow("网络接口", net?.name ?: "未连接")
                        KeyValueRow("本机地址", ip)
                        KeyValueRow(
                            "接入方式",
                            if (net?.name?.lowercase()?.startsWith("ap") == true) "手机热点" else "家庭 / 办公 Wi-Fi",
                            mono = false,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QuietAction("打开 Wi-Fi 设置", { ctl.openWifiSettings() }, Modifier.weight(1f))
                            QuietAction("热点设置", { ctl.openHotspotSettings() }, Modifier.weight(1f))
                        }
                        Text(
                            "没有路由器时，也可以开手机热点让电脑连过来。热点模式下手机地址通常是 192.168.43.1。",
                            style = MaterialTheme.typography.bodySmall,
                            color = cp.onPanelDim,
                        )
                    }
                    Panel(padding = 14.dp) {
                        Text(
                            "手机换网络后 IP 会变，二维码和地址要重新看。已在共享中的话，" +
                                "回「共享」页点一次「重启共享」。",
                            style = MaterialTheme.typography.bodySmall,
                            color = cp.onPanelDim,
                        )
                    }
                }

                3 -> {
                    StepTitle("设置共享", "开启后，局域网里的设备就能发现这台打印机。")
                    Panel {
                        KeyValueRow("共享名称", ctl.cfg.effectivePrinterName())
                        KeyValueRow("IPP 端口", "${st?.port ?: 8631}")
                        KeyValueRow("地址", st?.ippUri ?: "$ip:8631")
                        HLine()
                        SwitchRow(
                            title = "允许新设备",
                            subtitle = "局域网内默认信任；关掉后走 IP 白名单",
                            checked = cfg.allowNewDevices,
                            onChange = { v -> ctl.updateCfg { it.copy(allowNewDevices = v) } },
                        )
                        HLine()
                        PanelLabel("协议")
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
                                    2 -> ctl.say("IPP 是基础协议，不能关闭")
                                    3 -> ctl.updateCfg { it.copy(rawEnabled = !it.rawEnabled) }
                                }
                            },
                        )
                        if (rt.running) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Pill("共享运行中", Tone.Ok)
                                Spacer(Modifier.width(8.dp))
                                Mono("${rt.clients.count { it.fresh && !it.blocked }} 台设备曾接入", size = 10.sp, color = cp.onPanelDim)
                            }
                        } else {
                            PrimaryAction(
                                if (ctl.busy == "启动共享") "正在启动…" else "启动共享",
                                { ctl.startShare() },
                                Modifier.fillMaxWidth(),
                                enabled = ctl.busy == null,
                            )
                        }
                    }
                }

                else -> {
                    StepTitle("完成", "最后打一张测试页，确认从 USB 到纸面整条链路是通的。")
                    Panel {
                        Text(
                            "打印机已加入 ${net?.name ?: "局域网"}，可在手机 / 电脑上选择「${ctl.cfg.effectivePrinterName()} @ 手机网关」。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cp.onPanel,
                        )
                        Mono(st?.ippUri ?: "$ip:8631", size = 11.sp, color = cp.onPanelDim)
                        PrimaryAction(
                            if (ctl.busy == "打印测试页") "正在打印…" else "打印测试页",
                            {
                                ctl.printTestPage()
                                testMsg = "测试页已提交，几张后会出纸"
                            },
                            Modifier.fillMaxWidth(),
                            enabled = ctl.busy == null,
                        )
                        testMsg?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = cp.ok)
                        }
                        Text(
                            "出纸正常就说明渲染、USB 传输、机芯都通了。没出纸的话，回「维护 → 诊断」跑完整诊断。",
                            style = MaterialTheme.typography.bodySmall,
                            color = cp.onPanelDim,
                        )
                    }
                    Panel(padding = 14.dp) {
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Dot(cp.warn, 7.dp)
                            Text(
                                "共享依赖本 App 运行：请保持常驻通知、关闭省电限制，手机接充电器。",
                                style = MaterialTheme.typography.bodySmall,
                                color = cp.onPanelDim,
                            )
                        }
                    }
                }
            }
        }

        // ── 底部按钮 ────────────────────────────────────────────────────────
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blockReason?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = cp.onBgDim)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (step > 0) {
                    QuietAction("上一步", { step-- }, Modifier.weight(1f))
                }
                PrimaryAction(
                    text = if (step == 4) "开始使用" else "下一步",
                    onClick = {
                        if (step == 4) {
                            ctl.markWizardDone()
                            onFinish()
                        } else {
                            step++
                        }
                    },
                    enabled = canNext,
                    modifier = Modifier.weight(if (step > 0) 1.4f else 1f),
                )
            }
        }
    }
}

@Composable
private fun StepTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = cp.onBg)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cp.onBgDim)
    }
}

@Composable
private fun Capability(name: String, detail: String, warn: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .width(5.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (warn) cp.warn else cp.ok),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, color = cp.onPanel)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = cp.onPanelDim)
        }
    }
}
