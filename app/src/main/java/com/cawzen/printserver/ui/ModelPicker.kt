package com.cawzen.printserver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.window.Dialog
import com.cawzen.printserver.usb.PrinterModel

/**
 * 机型选择器。
 *
 * 列表分两类，第一项永远是「通用直通」：
 *
 *  - **渲染机型**（brlaser）：兄弟本体 + 采用同一机芯的贴牌机。括号里那些名字
 *    （联想 / 富士胶片 / 东芝 / 柯尼卡美能达）不是「另外支持」，而是同一台机器的
 *    另一个铭牌 —— 粉盒能互换意味着机芯相同，PCL 编码自然一样。
 *  - **通用直通**：不渲染，原样转发客户端排好版的 PCL/PostScript。这条与品牌无关。
 *
 * 搜索同时匹配型号名和贴牌名，否则用户搜「联想」会一无所获 —— 而这正是他要找的。
 */
@Composable
fun ModelPickerDialog(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val keyword = query.trim()
    val matched = if (keyword.isBlank()) {
        PrinterModel.CATALOG
    } else {
        PrinterModel.CATALOG.filter { m ->
            m.name.contains(keyword, ignoreCase = true) ||
                m.family.contains(keyword, ignoreCase = true) ||
                m.aliases.any { it.contains(keyword, ignoreCase = true) }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(cp.panel)
                .padding(vertical = 14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "选择打印机型号",
                        style = MaterialTheme.typography.titleLarge,
                        color = cp.onPanel,
                    )
                    Text(
                        "${PrinterModel.BRLASER_COUNT} 款可直接渲染，" +
                            "另覆盖 ${PrinterModel.ALIAS_COUNT} 款贴牌机",
                        style = MaterialTheme.typography.bodySmall,
                        color = cp.onPanelDim,
                    )
                }
                TextAction("关闭", onClick = onDismiss)
            }

            // 40 多项滚三屏，纯滚动找型号很折磨。搜「2240」「DCP」「联想」比翻列表快。
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = {
                    Text(
                        "搜索型号，例如 2240、DCP、联想",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cp.onPanelDim,
                    )
                },
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = cp.panelAlt,
                    unfocusedContainerColor = cp.panelAlt,
                    focusedBorderColor = cp.onPanelDim,
                    unfocusedBorderColor = cp.panelHair,
                    focusedTextColor = cp.onPanel,
                    unfocusedTextColor = cp.onPanel,
                    cursorColor = cp.onPanel,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 6.dp),
            )

            LazyColumn(Modifier.fillMaxWidth()) {
                if (keyword.isBlank()) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick("") }
                                .padding(horizontal = 18.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "自动识别",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = cp.onPanel,
                                )
                                Text(
                                    "按 USB 的 VID/PID 判断型号（推荐）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cp.onPanelDim,
                                )
                            }
                            if (current.isBlank()) Pill("当前", Tone.Ok)
                        }
                        HLine()
                    }
                }

                // 按 通用 / DCP / HL / MFC 分组，组内保持原表顺序。
                matched.groupBy { it.family }.forEach { (family, models) ->
                    item(key = "hdr-$family") {
                        Text(
                            family.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = cp.onPanelDim,
                            modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(models, key = { it.id }) { m ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(m.id) }
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    m.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = cp.onPanel,
                                    modifier = Modifier.weight(1f),
                                )
                                // 双面是选机型时最想知道的一件事，而它藏在 PPD 里。
                                // 直通模式与双面无关（换不换机芯都不知道），标「直通」。
                                when {
                                    m.driver == PrinterModel.Driver.PASSTHROUGH -> Tag("直通")
                                    m.duplex -> Tag("双面")
                                }
                                if (m.id == current) Pill("当前", Tone.Ok)
                            }
                            if (m.aliases.isNotEmpty()) {
                                Text(
                                    m.aliases.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cp.onPanelDim,
                                    fontSize = 11.sp,
                                )
                            }
                            if (m.note.isNotBlank()) {
                                Text(
                                    m.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (m.driver == PrinterModel.Driver.PASSTHROUGH) cp.warn else cp.onPanelDim,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }

                if (matched.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 26.dp),
                        ) {
                            Text(
                                "没有匹配的型号。如果这台是兄弟机芯，选一个同系列机型通常也能打" +
                                    "（编码一样）；其它品牌请选上面的「通用 PCL / PostScript 打印机」走直通。",
                                style = MaterialTheme.typography.bodySmall,
                                color = cp.onPanelDim,
                            )
                        }
                    }
                }
            }

            Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                Text(
                    "灰字是采用同一 Brother 机芯的贴牌机型（粉盒可互换 = 同机芯，编码一致）。" +
                        "型号只影响识别与显示名，不影响打印参数：分辨率 / 单双面 / 纸张仍按作业设置走。",
                    style = MaterialTheme.typography.bodySmall,
                    color = cp.onPanelDim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                )
            }
        }
    }
}

/** 小标签（描边，不用实心块 —— 列表里实心色块会抢走型号名的注意力）。 */
@Composable
private fun Tag(text: String) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .clip(shape)
            .border(1.dp, cp.panelHair, shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = cp.onPanelDim,
            letterSpacing = 0.sp,
        )
    }
}
