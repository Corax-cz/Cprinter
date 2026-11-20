package com.cawzen.printserver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cawzen.printserver.usb.PrinterProbe

/**
 * 首页：打印机状态面板 + 共享控制台。
 *
 * 信息层级照抄实体打印机的小屏：**状态词 → 型号 → 耗材/纸张 → 地址 → 队列**。
 * 用户第一眼要能看到「能不能打」，第二眼才是「怎么连」。
 */
@Composable
fun StatusScreen(
    rt: Runtime,
    ctl: UiController,
    go: (Tab) -> Unit,
) {
    val st = rt.status
    val running = rt.running
    val dev = rt.device
    val probe = ctl.probe
    // 服务停了也要显示手机自己的局域网地址，否则用户以为网络断了。
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val wifiIp = androidx.compose.runtime.remember(rt.status?.ippUri) {
        com.cawzen.printserver.net.NetInfo.wifi(ctx)?.ip ?: "未连接"
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── 标题 ────────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "我的打印机",
                    style = MaterialTheme.typography.headlineMedium,
                    color = cp.onBg,
                )
                Text(
                    when {
                        !dev.connected -> "未连接 · 用 USB OTG 线接打印机"
                        dev.manual -> dev.name + " · 手动指定"
                        dev.exact -> dev.name + " · 已识别"
                        else -> dev.name + " · 兼容模式"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = cp.onBgDim,
                )
            }
            Pill(
                text = if (dev.connected) "USB 已连接" else "USB 未连接",
                tone = if (dev.connected) Tone.Ok else Tone.Idle,
            )
        }

        // ── LCD 状态卡 ──────────────────────────────────────────────────────
        LcdCard(buildLcd(rt, probe))

        // 没有 USB 权限时，这是唯一能做的事，放最显眼的位置
        if (dev.connected && !dev.permission) {
            Panel {
                PanelLabel("需要授权")
                Text(
                    "Android 需要你确认一次 USB 访问权限，App 才能向打印机发数据。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cp.onPanel,
                )
                PrimaryAction(
                    text = "授权 USB 访问",
                    onClick = { ctl.requestUsbPermission() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ── 无线共享卡 ──────────────────────────────────────────────────────
        Panel {
            PanelLabel("无线共享") {
                Pill(
                    text = when {
                        running -> "运行中"
                        else -> "已停止"
                    },
                    tone = if (running) Tone.Ok else Tone.Idle,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        if (running) "手机作为网关，向局域网转发打印" else "启动后，同一网络下的设备即可打印",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cp.onPanel,
                    )
                    Mono(
                        text = if (running) {
                            "${wifiIp} · 已连接 ${rt.clients.count { it.fresh && !it.blocked }} 台"
                        } else {
                            "网络 $wifiIp"
                        },
                        size = 11.sp,
                        color = cp.onPanelDim,
                    )
                }
            }

            if (running) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QrCanvas(st?.ippUri ?: "", Modifier.size(104.dp))
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Mono(
                            text = st?.ippUri ?: "—",
                            size = 11.sp,
                            color = cp.onPanel,
                        )
                        Mono(
                            text = "IPP ${st?.port ?: 0}" +
                                if (st?.rawRunning == true) " · RAW ${st?.rawPort}" else "",
                            size = 10.sp,
                            color = cp.onPanelDim,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextAction("复制地址") { ctl.copy("IPP 地址", st?.ippUri ?: "") }
                            TextAction("状态页") { ctl.copy("状态页", st?.httpUri ?: "") }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) {
                    QuietAction(
                        text = "暂停共享",
                        onClick = { ctl.stopShare() },
                        tone = Tone.Bad,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    PrimaryAction(
                        text = "启动无线共享",
                        onClick = { ctl.startShare() },
                        enabled = dev.connected && dev.permission && ctl.busy == null,
                        secondary = if (!dev.connected) "先连接打印机" else null,
                        modifier = Modifier.weight(1f),
                    )
                }
                QuietAction(
                    text = if (ctl.busy == "打印测试页") "打印中…" else "打印测试页",
                    onClick = { ctl.printTestPage() },
                    enabled = dev.connected && ctl.busy == null,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ── 当前任务 ────────────────────────────────────────────────────────
        Panel(padding = 16.dp) {
            PanelLabel("当前任务") {
                if (rt.paused) Pill("队列已暂停", Tone.Warn)
            }
            val job = rt.activeJob
            if (job != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(job.name, style = MaterialTheme.typography.bodyLarge, color = cp.onPanel)
                        Mono(
                            "#${job.id} · ${job.kind} · ${job.copies} 份 · ${job.client.ifBlank { "本机" }}",
                            size = 11.sp,
                            color = cp.onPanelDim,
                        )
                    }
                    Pill("打印中", Tone.Ok)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(cp.panelHair),
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = cp.ok,
                        trackColor = cp.panelHair,
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (rt.jobs.isEmpty()) "没有正在打印的作业" else "${rt.jobs.size} 个作业在排队",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cp.onPanelDim,
                        modifier = Modifier.weight(1f),
                    )
                    Mono(
                        "队列 ${rt.jobs.size}",
                        size = 11.sp,
                        color = cp.onPanelDim,
                    )
                }
            }
        }

        // ── 网关提示（只在共享运行时说，避免变成常驻噪音）────────────────────
        if (running) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(cp.warn.copy(alpha = 0.10f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Dot(cp.warn, 7.dp)
                Text(
                    "手机是网关：无线共享依赖本 App 运行。清理后台或省电模式可能让共享中断，" +
                        "建议把这台手机插着充电器并允许 App 常驻通知。",
                    style = MaterialTheme.typography.bodySmall,
                    color = cp.warn,
                )
            }
        }

        // ── 型号与兼容性 ────────────────────────────────────────────────────
        if (!dev.connected) {
            Panel(padding = 16.dp) {
                PanelLabel("支持的机型")
                Text(
                    "${com.cawzen.printserver.usb.PrinterModel.BRLASER_COUNT} 款兄弟机芯可直接渲染" +
                        "（含联想 / 富士胶片 / 东芝 / 柯美的同机芯贴牌机）；" +
                        "其它品牌用「通用 PCL / PostScript」直通，不渲染、原样转发。" +
                        "接上 USB 后按 VID/PID 自动识别，识别不出来可以手动指定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = cp.onPanelDim,
                )
                if (dev.otherDevices > 0) {
                    Mono(
                        "检测到 ${dev.otherDevices} 个非打印机 USB 设备",
                        size = 11.sp,
                        color = cp.onPanelDim,
                    )
                }
                if (ctl.cfg.printerModelId.isNotBlank()) {
                    Mono(
                        "当前手动指定：" +
                            (com.cawzen.printserver.usb.PrinterModel.byId(ctl.cfg.printerModelId)?.name ?: "—"),
                        size = 11.sp,
                        color = cp.onPanelDim,
                    )
                }
                Row { TextAction("选择机型") { go(Tab.Settings) } }
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

/** 设备小屏的信息层级：状态词优先，其次才是细节。 */
private fun buildLcd(rt: Runtime, probe: PrinterProbe.Snapshot?): LcdModel {
    val dev = rt.device
    val st = rt.status
    val addr = hostPort(st?.ippUri ?: "")
    val active = rt.activeJob
    val queueLine = when {
        rt.paused -> "QUEUE PAUSED · 队列 ${rt.jobs.size}"
        active != null -> "PRINTING #${active.id} · 队列 ${rt.jobs.size}"
        rt.jobs.isNotEmpty() -> "QUEUED ${rt.jobs.size} 个作业"
        rt.running -> "IDLE · 队列 0"
        else -> "SHARING OFF · 共享未启动"
    }

    if (!dev.connected) {
        return LcdModel(
            word = "OFFLINE",
            subtitle = "未检测到打印机",
            tone = Tone.Idle,
            modelName = "—",
            usbOn = false,
            tonerKnown = false,
            tonerFilled = 0,
            paper = "—",
            address = addr,
            queueLine = queueLine,
            hint = "用 USB OTG 线连接打印机并开机。",
        )
    }

    // 状态来源优先级：刚读过的端口状态 > 上次作业时顺手读的 > 只知道有设备
    val lastText = st?.portStatus ?: ""
    val paperEmpty = probe?.ok == true && probe.paperEmpty
    val hardwareError = probe?.ok == true && (probe.error || probe.offline)
    // 端口状态文案已改为 "0x18 就绪" / "0x20 缺纸" 这类中文，别再按英文位名匹配。
    val cachedPaper = lastText.contains("缺纸")
    val cachedError = lastText.contains("故障") || lastText.contains("离线")

    val (word, zh, tone, hint) = when {
        paperEmpty -> Quad("PAPER OUT", "缺纸", Tone.Warn, "E02 缺纸：装好纸张后点「打印测试页」重试。")
        hardwareError -> Quad("ERROR", "打印机故障", Tone.Bad, "E04 打印机报故障或离线：检查电源、盖板、卡纸，然后点「复位打印机」。")
        cachedPaper -> Quad("NO PAPER", "缺纸", Tone.Warn, "E02 上次作业时检测到缺纸：装纸后重试。")
        cachedError -> Quad("CHECK", "需要检查", Tone.Warn, "上次检测到故障/离线状态：到「维护 → 诊断」确认。")
        !dev.permission -> Quad("LOCKED", "待授权", Tone.Warn, "Android 还没有授予 USB 访问权限。")
        probe?.ok == false -> Quad("READY", "就绪", Tone.Ok, null)
        else -> Quad("READY", "就绪", Tone.Ok, null)
    }

    return LcdModel(
        word = word,
        subtitle = zh,
        tone = tone,
        modelName = dev.name,
        usbOn = true,
        // 粉量真的读不到：这台机的 USB 打印机类接口没有耗材查询通道。
        tonerKnown = false,
        tonerFilled = 0,
        paper = "A4",
        address = addr,
        queueLine = queueLine,
        hint = hint,
    )
}

private data class Quad(val a: String, val b: String, val c: Tone, val d: String?)

private fun hostPort(uri: String): String {
    if (uri.isBlank() || uri == "-") return "—"
    return uri.removePrefix("ipp://").substringBefore("/").ifBlank { "—" }
}

