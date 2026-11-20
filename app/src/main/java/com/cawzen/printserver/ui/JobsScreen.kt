package com.cawzen.printserver.ui

import android.app.Activity
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 任务页：队列 + 历史。
 *
 * 队列里只放「还没结束」的作业（PENDING / PROCESSING），历史里放已经走到终态的。
 * 这两类分开显示不是为了好看，而是因为用户对它们要做的事完全不同：队列要的是
 * 「取消 / 暂停」，历史要的是「当时为什么失败、能不能重试」。
 */
@Composable
fun JobsScreen(rt: Runtime, ctl: UiController) {
    var tabIndex by remember { mutableStateOf(0) }
    var expanded by remember { mutableStateOf<Int?>(null) }
    val queue = rt.jobs
    val history = rt.history

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("任务", style = MaterialTheme.typography.headlineMedium, color = cp.onBg)
                    Text(
                        "队列串行执行：同一时刻只有一份文档能写 USB",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cp.onBgDim,
                    )
                }
                if (queue.isNotEmpty()) {
                    Pill("${queue.size} 个在队列", if (rt.paused) Tone.Warn else Tone.Ok)
                }
            }
        }

        item {
            Segmented(
                options = listOf("队列 ${queue.size}", "历史 ${history.size}"),
                selected = tabIndex,
                onSelect = { tabIndex = it },
            )
        }

        if (tabIndex == 0) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuietAction(
                        text = if (rt.paused) "恢复队列" else "暂停队列",
                        onClick = { ctl.setQueuePaused(!rt.paused) },
                        enabled = ctl.busy == null,
                        modifier = Modifier.weight(1f),
                    )
                    QuietAction(
                        text = "打印测试页",
                        onClick = { ctl.printTestPage() },
                        enabled = rt.device.connected && ctl.busy == null,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item { LocalFilePanel(ctl) }

            if (queue.isEmpty()) {
                item {
                    EmptyState(
                        title = "队列是空的",
                        body = "用上面的「选择文件」打手机里的文档；从 iPhone / 电脑打印时，" +
                            "作业也会先出现在这里，再串行写进打印机。手机作为网关时要保持 App 运行。",
                        glyph = Tab.Jobs,
                    )
                }
            } else {
                items(queue, key = { it.id }) { job ->
                    JobCard(
                        job = job,
                        expanded = expanded == job.id,
                        onToggle = { expanded = if (expanded == job.id) null else job.id },
                        onCancel = { ctl.cancelJob(job.id) },
                        onRetry = { ctl.retryJob(job.id) },
                    )
                }
            }
        } else {
            if (history.isEmpty()) {
                item {
                    EmptyState(
                        title = "还没有打印记录",
                        body = "完成或失败的作业会留在这里，包括失败的原始原因 —— " +
                            "排查「为什么没出纸」时先看这里。",
                        glyph = Tab.Jobs,
                        // 让空状态吃掉剩余的竖向空间并居中，否则下半屏是一大块空灰。
                        modifier = Modifier.fillParentMaxHeight(0.6f),
                    )
                }
            } else {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextAction("清空历史") { ctl.clearHistory() }
                    }
                }
                items(history, key = { "${it.id}-${it.createdAt}" }) { job ->
                    JobCard(
                        job = job,
                        expanded = expanded == job.id,
                        onToggle = { expanded = if (expanded == job.id) null else job.id },
                        onCancel = null,
                        onRetry = { ctl.retryJob(job.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalFilePanel(ctl: UiController) {
    // 用 StartActivityForResult 自己拼 Intent，不用现成的 OpenMultipleDocuments：
    //
    // 1. 需要 `EXTRA_INITIAL_URI` 直接把用户丢到「下载」目录。手机文件系统动辄几十层，
    //    让用户"自己去找到文件"是这类功能最常见的失败点 —— 打开就落在他要找的地方，
    //    成功率差一个数量级。现成的 ActivityResultContract 没有这个参数。
    // 2. mime 用 `*/*` 而不是 pdf：SAF 里 PCL / PostScript 没有统一 MIME，按 MIME
    //    过滤只会让文件在选择器里变灰。类型交给字节嗅探判断（见 DocumentSniffer）。
    //
    // 实测这个 ROM（ColorOS）会用自己的 FileManager 接管选择器，而它切到「文件」
    // 分栏时会直接跳回桌面组件 —— 指定初始目录正好绕开那一步。
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val data = res.data ?: return@rememberLauncherForActivityResult
        val uris = buildList {
            data.clipData?.let { c -> for (i in 0 until c.itemCount) add(c.getItemAt(i).uri) }
            if (isEmpty()) data.data?.let { add(it) }
        }
        if (uris.isNotEmpty()) ctl.printLocalFiles(uris)
    }

    Panel {
        PanelLabel("本机文件")
        Spacer(Modifier.height(10.dp))
        Text(
            "选手机里的 PDF、图片或 PCL，直接从这台手机打出来，不用经过局域网",
            style = MaterialTheme.typography.bodyMedium,
            color = cp.onPanel,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            "类型按文件内容判断，不看扩展名。作业会进上面的队列，进度和失败原因都在那里。",
            style = MaterialTheme.typography.bodySmall,
            color = cp.onPanelDim,
        )
        Spacer(Modifier.height(14.dp))
        PrimaryAction(
            text = if (ctl.busy == "读取文件") "正在读取…" else "选择文件",
            onClick = {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    runCatching {
                        putExtra(
                            DocumentsContract.EXTRA_INITIAL_URI,
                            DocumentsContract.buildDocumentUri(
                                "com.android.externalstorage.documents",
                                "primary:Download",
                            ),
                        )
                    }
                }
                runCatching { picker.launch(intent) }
                    .onFailure { ctl.say("打不开文件选择器：${it.message?.take(60)}") }
            },
            enabled = ctl.busy == null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun JobCard(
    job: JobRow,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCancel: (() -> Unit)?,
    onRetry: () -> Unit,
) {
    Panel(padding = 14.dp, onClick = onToggle) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    job.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = cp.onPanel,
                    maxLines = 2,
                )
                Mono(
                    "#${job.id} · ${job.kind} · ${job.pages.coerceAtLeast(0)} 页" +
                        (if (job.copies > 1) " × ${job.copies} 份" else "") +
                        " · " + (job.client.ifBlank { job.user.ifBlank { "本机" } }),
                    size = 10.sp,
                    color = cp.onPanelDim,
                )
            }
            Pill(job.stateLabel, job.tone)
        }

        if (job.running) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = cp.ok,
                trackColor = cp.panelHair,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Mono(
                when {
                    job.running || job.pending -> "提交于 ${fmtTime(job.createdAt)}"
                    else -> "结束于 ${fmtDateTime(job.finishedAt)}"
                },
                size = 10.sp,
                color = cp.onPanelDim,
                modifier = Modifier.weight(1f),
            )
            Mono(fmtBytes(job.bytes), size = 10.sp, color = cp.onPanelDim)
        }

        if (job.failed) {
            Text(
                job.failureHint,
                style = MaterialTheme.typography.bodySmall,
                color = cp.bad,
            )
        }

        if (expanded) {
            HLine()
            if (job.message.isNotBlank()) {
                Mono(
                    job.message.trim().take(1200),
                    size = 9.sp,
                    color = cp.onPanelDim,
                )
            } else {
                Mono("（这条作业没有附加日志）", size = 10.sp, color = cp.onPanelDim)
            }
            Mono("文档 ${job.filePath.substringAfterLast('/')}", size = 9.sp, color = cp.onPanelDim)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onCancel != null && (job.pending || job.running)) {
                QuietAction("取消", onCancel, Modifier.weight(1f), tone = Tone.Bad)
            }
            if (job.failed) {
                QuietAction(
                    if (job.filePath.isBlank()) "无法重试" else "重试",
                    onRetry,
                    Modifier.weight(1f),
                )
            }
            if (!expanded) {
                TextAction("详情", onClick = onToggle)
            }
        }
    }
}
