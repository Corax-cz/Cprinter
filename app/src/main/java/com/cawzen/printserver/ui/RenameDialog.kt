package com.cawzen.printserver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.cawzen.printserver.AppSettings

/**
 * 给打印机改名。
 *
 * 名字会同时成为 **mDNS 服务实例名**和 IPP 的 `printer-name` —— 也就是说，
 * iPhone 打印面板、Windows 打印机列表、Android 默认打印服务里看到的那一行字，
 * 就是这里填的东西。它不是一个纯展示字段，改它等于换了一个 mDNS 服务：
 *
 * - 实例名是 DNS 标签，硬上限 63 **字节**（一个汉字 3 字节），不是 63 个字符；
 * - 名字里不能有点（会被当成标签分隔符），这里统一换成短横线；
 * - 改完必须注销再注册，客户端那边的旧记录要等 TTL 才消失，所以老设备上
 *   很可能出现"新旧两条记录" —— 这也是对话框里那句提示存在的原因。
 *
 * 校验放在保存前而不是输入时：边输入边规整会让光标乱跳（打一个空格就被吃掉）。
 */
@Composable
fun RenameDialog(
    current: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 用 TextFieldValue 而不是 String：初始内容选中全部，用户直接打字就是替换，
    // 想在里面加字也只要按一下方向键 —— 改名场景里 90% 是"整个换掉"。
    var field by remember {
        mutableStateOf(
            TextFieldValue(
                text = current,
                selection = TextRange(0, current.length),
            )
        )
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val raw = field.text
    val cleaned = AppSettings.normalizePrinterName(raw)
    val shown = cleaned.ifBlank { AppSettings.DEFAULT_PRINTER_NAME }
    val bytes = AppSettings.nameBytes(shown)
    val tooLong = bytes > AppSettings.MAX_PRINTER_NAME_BYTES
    val rewritten = raw.isNotBlank() && cleaned != raw.trim()

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(cp.panel)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("给打印机改名", style = MaterialTheme.typography.titleLarge, color = cp.onPanel)
            Text(
                "打印面板、系统打印机列表里显示的就是这个名字。",
                style = MaterialTheme.typography.bodySmall,
                color = cp.onPanelDim,
            )

            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(12.dp),
                placeholder = {
                    Text(
                        AppSettings.DEFAULT_PRINTER_NAME,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cp.onPanelDim,
                    )
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { if (!tooLong) onSave(cleaned) },
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = cp.panelAlt,
                    unfocusedContainerColor = cp.panelAlt,
                    focusedBorderColor = if (tooLong) cp.warn else cp.onPanelDim,
                    unfocusedBorderColor = if (tooLong) cp.warn else cp.panelHair,
                    focusedTextColor = cp.onPanel,
                    unfocusedTextColor = cp.onPanel,
                    cursorColor = cp.onPanel,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (rewritten) "会保存为「$cleaned」（点号换成短横线）" else "空着就用默认名",
                    style = MaterialTheme.typography.bodySmall,
                    color = cp.onPanelDim,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "$bytes / ${AppSettings.MAX_PRINTER_NAME_BYTES}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (tooLong) cp.warn else cp.onPanelDim,
                )
            }

            if (tooLong) {
                Text(
                    "超出 ${bytes - AppSettings.MAX_PRINTER_NAME_BYTES} 字节。名字是 mDNS 的" +
                        "一个 DNS 标签，上限 63 字节，一个汉字占 3 字节。",
                    style = MaterialTheme.typography.bodySmall,
                    color = cp.warn,
                )
            }

            Text(
                "改名后共享会重启一次。已经在设备上添加过这台打印机的（Windows 尤其），" +
                    "列表里可能多出一条旧名字的记录，删掉旧的即可 —— 那不是多了一台机器。",
                style = MaterialTheme.typography.bodySmall,
                color = cp.onPanelDim,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                QuietAction(
                    "恢复默认",
                    { onSave("") },
                    Modifier.weight(1f),
                )
                PrimaryAction(
                    "保存",
                    { onSave(cleaned) },
                    Modifier.weight(1f),
                    enabled = !tooLong,
                )
            }
            Text(
                "恢复默认会清掉自定义名称，回到「${AppSettings.DEFAULT_PRINTER_NAME}」。",
                style = MaterialTheme.typography.bodySmall,
                color = cp.onPanelDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
