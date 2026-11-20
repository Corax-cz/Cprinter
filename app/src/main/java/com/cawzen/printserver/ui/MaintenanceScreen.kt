package com.cawzen.printserver.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cawzen.printserver.diag.Diagnostics

/**
 * 维护页。
 *
 * 这一页最重要的品质是**不撒谎**。这台机器（Brother HL-2240D 机芯）通过
 * USB 打印机类接口只能回一个字节的端口状态位，读不到粉量、页数、面板菜单，
 * 也没有喷墨机那种清洗/校准。所以：
 *
 * - 粉量显示「不可读」，并提供「记录更换粉盒时间」这个真实可做的事；
 * - 纸张状态来自真实的状态位，读不到就说读不到；
 * - 错误码是**本 App 依据状态位推导的编号**，界面里明确写出这一点，
 *   不让用户拿它去对打印机面板（对不上会更困惑）；
 * - 清洗/校准明确标注「本机型不支持」，不留一个点了没反应的按钮。
 */
@Composable
fun MaintenanceScreen(rt: Runtime, ctl: UiController) {
    val st = rt.status
    val probe = ctl.probe
    val report = ctl.report
    val cfg = ctl.cfg

    val cached = st?.portStatus ?: ""
    // 优先用 probe 解出的三态；字符串匹配只作兜底（旧值可能还停在手边）。
    // 注意端口状态现在是 "0x18 就绪" 这种中文文案，别再按英文位名匹配。
    val paperEmpty = probe?.paperEmpty == true || cached.contains("缺纸")
    val hardwareFault = probe?.let { it.ok && (it.error || it.offline) } == true ||
        cached.contains("故障") || cached.contains("离线")

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
                Text("维护", style = MaterialTheme.typography.headlineMedium, color = cp.onBg)
                Text(
                    rt.device.name.ifBlank { "未连接打印机" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = cp.onBgDim,
                )
            }
            Pill(
                if (paperEmpty) "缺纸" else if (hardwareFault) "需检查" else "正常",
                if (paperEmpty) Tone.Warn else if (hardwareFault) Tone.Bad else Tone.Ok,
            )
        }

        // ── 耗材 ────────────────────────────────────────────────────────────
        SectionTitle("耗材")
        Panel {
            PanelLabel("粉量") {
                Mono("不可读", size = 10.sp, color = cp.warn)
            }
            SegBar(
                segments = 6,
                filled = 0,
                color = cp.ok,
                known = false,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "这台打印机的 USB 接口只回 1 字节端口状态（缺纸 / 故障 / 离线），" +
                    "没有耗材余量通道，打印机面板也不对外提供。这里不画假的格数。",
                style = MaterialTheme.typography.bodySmall,
                color = cp.onPanelDim,
            )
            HLine()
            KeyValueRow(
                "更换记录",
                if (cfg.tonerChangedAt > 0) fmtDate(cfg.tonerChangedAt) else "未记录",
            )
            QuietAction("记录「已更换粉盒」", { ctl.markTonerChanged() }, Modifier.fillMaxWidth())
            Text(
                "粉盒实际剩余只能看纸面浓淡和打印机面板的 Toner 灯。记录时间是为了" +
                    "下次变淡时能估出用了多久。",
                style = MaterialTheme.typography.bodySmall,
                color = cp.onPanelDim,
            )
        }

        // ── 纸张 ────────────────────────────────────────────────────────────
        SectionTitle("纸张")
        Panel {
            KeyValueRow("尺寸", "A4（210 × 297 mm）")
            KeyValueRow(
                "纸盒状态",
                when {
                    probe == null && cached.isBlank() -> "未读取"
                    paperEmpty -> "缺纸"
                    else -> "有纸"
                },
                mono = false,
                valueColor = if (paperEmpty) cp.warn else cp.onPanel,
            )
            KeyValueRow("分辨率", "600 dpi（机芯物理上限）")
            Text(
                "IPP 只声明 A4：纸盒里放的就是 A4，声明支持多种尺寸只会让客户端" +
                    "送来信箱/legal 的版面，打出来被裁。",
                style = MaterialTheme.typography.bodySmall,
                color = cp.onPanelDim,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietAction(
                    if (ctl.busy == "读取状态") "读取中…" else "刷新状态",
                    { ctl.probeNow() },
                    Modifier.weight(1f),
                    enabled = rt.device.connected && ctl.busy == null,
                )
                QuietAction(
                    "复位打印机",
                    { ctl.recoverPrinter() },
                    Modifier.weight(1f),
                    enabled = rt.device.connected && ctl.busy == null,
                )
            }
            probe?.let { p ->
                HLine()
                Mono(
                    when {
                        p.ok -> "状态位 0x%02X · %s".format(p.raw, p.text)
                        else -> "读取失败：${p.note}"
                    },
                    size = 10.sp,
                    color = if (p.ok && !p.paperEmpty) cp.onPanelDim else cp.warn,
                )
                Mono("读取于 ${fmtTime(p.at)}", size = 10.sp, color = cp.onPanelDim)
            }
        }

        // ── 错误码 ──────────────────────────────────────────────────────────
        SectionTitle("错误码")
        Panel {
            Text(
                "下面的编号是本 App 依据 USB 状态位与作业结果推导的，不是打印机面板的原始码。" +
                    "面板上的灯语另有一套，两者不要混着对。",
                style = MaterialTheme.typography.bodySmall,
                color = cp.onPanelDim,
            )
            ErrorCode("E01", "打印机不可用", "USB 被拔出 / 打印机没开机 / 接口被其它 App 占用。检查线材与电源后重试。", rt.device.connected)
            HLine()
            ErrorCode("E02", "缺纸", "打开纸盒装纸，压下纸张直到纸盒到位，然后点「打印测试页」。", !paperEmpty)
            HLine()
            ErrorCode("E03", "USB 传输中断 / 打印机拒收", "两种成因：主机端点卡死（拔插 USB 线）；或打印机在 NAK、一个字节都不收——最常见是纸盒没推到位或盖板没关好（此时面板仍可能显示就绪）。「复位打印机」会先试 SOFT_RESET + 重开连接 + UEL。", true)
            HLine()
            ErrorCode("E04", "打印机故障 / 离线", "检查盖板是否关好、是否卡纸；面板灯常亮红时断电 10 秒再开。", !hardwareFault)
            HLine()
            ErrorCode("E05", "卡纸", "打开后盖取出纸张，恢复后点「重试」。本机型没有卡纸传感器位，这条靠作业失败 + 面板灯判断。", true)
            HLine()
            ErrorCode("E09", "未知错误", "到下面「诊断」跑一次完整诊断，报告里会带上渲染与传输的实际结果。", true)
        }

        // ── 诊断 ────────────────────────────────────────────────────────────
        SectionTitle("诊断")
        Panel {
            Text(
                "「完整诊断」会真的渲染一页 A4 并编码成 PCL，但不写 USB —— 不耗纸，" +
                    "能区分「数据不对」和「送不进去」。约 3~10 秒。",
                style = MaterialTheme.typography.bodySmall,
                color = cp.onPanelDim,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietAction(
                    if (ctl.diagRunning) "诊断中…" else "快速诊断",
                    { ctl.runDiagnostics(false) },
                    Modifier.weight(1f),
                    enabled = !ctl.diagRunning,
                )
                QuietAction(
                    "完整诊断",
                    { ctl.runDiagnostics(true) },
                    Modifier.weight(1f),
                    enabled = !ctl.diagRunning,
                )
            }
            report?.let { r ->
                HLine()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Mono(
                        "报告 ${fmtTime(r.at)} · 耗时 ${r.elapsedMs}ms",
                        size = 10.sp,
                        color = cp.onPanelDim,
                        modifier = Modifier.weight(1f),
                    )
                    TextAction("复制") { ctl.copy("诊断报告", r.toText()) }
                }
                r.items.forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Spacer(Modifier.width(2.dp))
                        Dot(item.level.color(), 6.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = cp.onPanel,
                            )
                            Mono(item.detail, size = 10.sp, color = cp.onPanelDim)
                        }
                    }
                }
            }
        }

        // ── 不可用的维护项 ──────────────────────────────────────────────────
        SectionTitle("不支持的项目")
        Panel {
            Text(
                "清洗 / 深度清洁 / 校准",
                style = MaterialTheme.typography.bodyLarge,
                color = cp.onPanelDim,
            )
            Text(
                "这是黑白激光机芯，没有喷头，不存在清洗；校准由机芯内部自动完成，" +
                    "USB 通道也没有对应命令。留一个点了没反应的按钮比不显示更糟，所以这里只说明。",
                style = MaterialTheme.typography.bodySmall,
                color = cp.onPanelDim,
            )
            HLine()
            Text("打印机原生面板", style = MaterialTheme.typography.bodyLarge, color = cp.onPanelDim)
            Text(
                "本机型不能用 USB 读取原生菜单，也没有可远程触发的按键。" +
                    "所以 App 里不会出现「仿实体按键」的面板页 —— 那只会让人以为按得动。",
                style = MaterialTheme.typography.bodySmall,
                color = cp.onPanelDim,
            )
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ErrorCode(code: String, title: String, fix: String, healthy: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Mono(
            code,
            size = 11.sp,
            color = if (healthy) cp.onPanelDim else cp.warn,
            modifier = Modifier.width(30.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = cp.onPanel)
            Text(fix, style = MaterialTheme.typography.bodySmall, color = cp.onPanelDim)
        }
    }
}

@Composable
private fun Diagnostics.Level.color() = when (this) {
    Diagnostics.Level.OK -> cp.ok
    Diagnostics.Level.WARN -> cp.warn
    Diagnostics.Level.FAIL -> cp.bad
    Diagnostics.Level.INFO -> cp.idle
}
