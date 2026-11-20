package com.cawzen.printserver.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 状态语义。整个 App 里只有这四种「有色」的东西。 */
enum class Tone { Ok, Warn, Bad, Idle }

@Composable
fun Tone.color(): Color = when (this) {
    Tone.Ok -> cp.ok
    Tone.Warn -> cp.warn
    Tone.Bad -> cp.bad
    Tone.Idle -> cp.idle
}

// ── 容器 ────────────────────────────────────────────────────────────────────

/** 白色卡片。整个界面的基本单元。 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 浅色下给一点点投影，白卡片才不像"贴在墙上的一张纸"；暗色下投影是脏的，跳过。
            .then(if (cp.dark) Modifier else Modifier.shadow(1.dp, shape, clip = false))
            .clip(shape)
            .background(cp.panel)
            .border(1.dp, cp.panelHair, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** 面板内的小标题：小字号 + 宽字距，像设备面板上的丝印。 */
@Composable
fun PanelLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = cp.onPanelDim,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** 页面级小标题（在卡片外面）。 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = cp.onBgDim,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

// ── 文本 ────────────────────────────────────────────────────────────────────

/** 等宽文本：IP、端口、错误码、计数一律走这里，强化「打印机面板」的手感。 */
@Composable
fun Mono(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 13.sp,
    color: Color = cp.onPanel,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: TextUnit = 0.sp,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = size,
        fontWeight = weight,
        letterSpacing = letterSpacing,
    )
}

@Composable
fun Pill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tone.color().copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Dot(tone.color(), 5.dp)
        Text(
            text,
            color = tone.color(),
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun Dot(color: Color, size: Dp = 7.dp) {
    Box(Modifier.size(size).background(color, CircleShape))
}

@Composable
fun HLine(modifier: Modifier = Modifier, color: Color = cp.panelHair) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

// ── 按钮 ────────────────────────────────────────────────────────────────────

/** 主操作按钮。高度 56dp，和实体打印机的面板键一样好按。 */
@Composable
fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: Tone? = null,
    secondary: String? = null,
) {
    val bg = if (tone != null) tone.color() else cp.accent
    val fg = if (tone != null) Color(0xFF0A0D11) else cp.onAccent
    // 禁用态不能只是把深色 key 调透明 —— 半透明的近黑色在白卡上是一块脏灰。
    // 换成中性面板色 + 描边，看起来是「还不能按」，而不是「按钮坏了」。
    val fill = when {
        enabled -> bg
        tone != null -> tone.color().copy(alpha = 0.18f)
        else -> cp.panelAlt
    }
    val ink = when {
        enabled -> fg
        tone != null -> fg.copy(alpha = 0.45f)
        else -> cp.onPanelDim
    }
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(fill)
            .then(if (!enabled && tone == null) Modifier.border(1.dp, cp.panelHair, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            color = ink,
            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        secondary?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                it,
                color = ink.copy(alpha = 0.72f),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 次级按钮：白底 + 1dp 描边。
 *
 * 原来用 `panelAlt`（#F1F3F6）当底，落在 `bg`（#F3F4F6）上几乎同色 —— 任务页那排
 * 「暂停队列 / 打印测试页」看起来像两行裸文字，不像按钮。白底描边在两种底色
 * （bg 与 panel）上都能立住。
 */
@Composable
fun QuietAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: Tone? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(if (cp.dark) cp.panelAlt else cp.panel)
            .border(1.dp, cp.panelHair, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (!enabled) cp.onPanelDim.copy(alpha = 0.5f) else (tone?.color() ?: cp.onPanel),
            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
        )
    }
}

/** 小号文字按钮（卡片底部的次要入口）。 */
@Composable
fun TextAction(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        color = if (enabled) cp.onBg else cp.onBgDim,
        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
    )
}

// ── 行 ──────────────────────────────────────────────────────────────────────

@Composable
fun KeyValueRow(
    label: String,
    value: String,
    mono: Boolean = true,
    valueColor: Color = cp.onPanel,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = cp.onPanelDim,
            modifier = Modifier.width(76.dp),
        )
        if (mono) {
            Mono(value, size = 13.sp, color = valueColor, modifier = Modifier.weight(1f))
        } else {
            Text(
                value,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = valueColor,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                color = if (enabled) cp.onPanel else cp.onPanelDim,
            )
            subtitle?.let {
                Text(
                    it,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = cp.onPanelDim,
                )
            }
        }
        SmallSwitch(checked = checked, enabled = enabled, onChange = onChange)
    }
}

@Composable
private fun SmallSwitch(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val track = if (checked) cp.ok else cp.panelHair
    val knob = if (checked) Color(0xFF07130C) else cp.onPanelDim
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(CircleShape)
            .background(if (enabled) track else track.copy(alpha = 0.4f))
            .clickable(enabled = enabled) { onChange(!checked) },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 3.dp)
                .size(20.dp)
                .background(if (enabled) knob else knob.copy(alpha = 0.5f), CircleShape),
        )
    }
}

/**
 * 分段选择：两三个互斥选项。
 *
 * 选中态用**白底 + 描边**而不是深色块 —— 深色块在浅色页面上等于又开了一个
 * 「黑一块」的洞，与状态屏抢注意力。白底滑块是 iOS 分段的常见做法，容器本身
 * 已经是 `panelAlt` 灰，白块自然浮起来。
 */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val outer = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(outer)
            .background(cp.panelAlt)
            .border(1.dp, cp.panelHair, outer)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val inner = RoundedCornerShape(9.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(inner)
                    .background(if (on) cp.panel else Color.Transparent)
                    .then(if (on) Modifier.border(1.dp, cp.panelHair, inner) else Modifier)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (on) cp.onPanel else cp.onPanelDim,
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

/** 细分段（协议开关那种，选项更多、字形更小）。 */
@Composable
fun ChipGroup(
    options: List<Pair<String, Boolean>>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEachIndexed { i, (label, on) ->
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) cp.ok.copy(alpha = 0.12f) else cp.panelAlt)
                    .border(
                        1.dp,
                        if (on) cp.ok.copy(alpha = 0.45f) else cp.panelHair,
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onToggle(i) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Dot(if (on) cp.ok else cp.idle, 5.dp)
                Spacer(Modifier.width(5.dp))
                Text(
                    label,
                    color = if (on) cp.onPanel else cp.onPanelDim,
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

// ── 用量条（墨量那种）───────────────────────────────────────────────────────

/**
 * 分段条。
 *
 * 粉量这类**读不到**的量用 [known=false] 画成全空 + 短横，而不是画满五格：
 * 画满等于撒谎，画空又像「墨用完了」。虚线感更接近面板上的「——」。
 */
@Composable
fun SegBar(
    segments: Int,
    filled: Int,
    color: Color,
    modifier: Modifier = Modifier,
    known: Boolean = true,
) {
    Canvas(modifier.height(10.dp).fillMaxWidth()) {
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (segments - 1)) / segments
        for (i in 0 until segments) {
            val left = i * (w + gap)
            if (!known) {
                drawRect(
                    color = color.copy(alpha = 0.30f),
                    topLeft = Offset(left, 0f),
                    size = Size(w, size.height),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx())),
                    ),
                )
            } else {
                drawRect(
                    color = if (i < filled) color else color.copy(alpha = 0.16f),
                    topLeft = Offset(left, 0f),
                    size = Size(w, size.height),
                )
            }
        }
    }
}

// ── LCD 状态卡 ──────────────────────────────────────────────────────────────

data class LcdModel(
    /** 大字号状态词，英文（打印机面板的语言）。 */
    val word: String,
    /** 中文状态词。 */
    val subtitle: String,
    val tone: Tone,
    val modelName: String,
    val usbOn: Boolean,
    val tonerKnown: Boolean,
    /** 0..5，未知时忽略。 */
    val tonerFilled: Int,
    val paper: String,
    val address: String,
    val queueLine: String,
    /** 错误码与建议（有错时显示在最下面一行）。 */
    val hint: String? = null,
)

/**
 * 仿 LCD 的状态屏。
 *
 * 结构是「白色卡片里嵌一块屏」：卡片负责和其它内容对齐，屏幕负责像设备面板。
 * 之前整张卡片就是深灰底，一屏里好几块深色，反而看不出哪块是「屏」。现在
 * 深色只出现这一处，焦点就落对了。
 *
 * 屏内细节：等宽字体、宽字距、极轻的点阵纹理、内描边。状态色只改文字，不改底 ——
 * 底色一变色就像告警横幅，反而失去了「屏幕」的感觉。
 */
@Composable
fun LcdCard(model: LcdModel, modifier: Modifier = Modifier) {
    Panel(modifier, padding = 10.dp) {
        val shape = RoundedCornerShape(12.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(cp.lcdBg)
                .border(1.dp, cp.lcdFgDim.copy(alpha = 0.22f), shape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            val toneColor = model.tone.onLcd()

            // 顶行：状态词 + USB 指示
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(toneColor, 9.dp)
                Spacer(Modifier.width(8.dp))
                Mono(
                    model.word,
                    size = 25.sp,
                    color = toneColor,
                    weight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.weight(1f))
                Mono(
                    if (model.usbOn) "USB ●" else "USB ○",
                    size = 11.sp,
                    color = cp.lcdFgDim,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Mono(model.subtitle, size = 13.sp, color = toneColor.copy(alpha = 0.9f))
                Spacer(Modifier.weight(1f))
                Mono(model.modelName, size = 11.sp, color = cp.lcdFgDim)
            }

            HLine(color = cp.lcdFgDim.copy(alpha = 0.22f))

            // 粉量 / 纸张
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Mono("TONER", size = 10.sp, color = cp.lcdFgDim, letterSpacing = 1.sp)
                Box(Modifier.weight(1f)) {
                    SegBar(
                        segments = 6,
                        filled = model.tonerFilled,
                        color = if (model.tone == Tone.Bad) cp.bad.onLcd() else cp.ok.onLcd(),
                        known = model.tonerKnown,
                    )
                }
                Mono(
                    if (model.tonerKnown) "${model.tonerFilled}/6" else "—",
                    size = 11.sp,
                    color = cp.lcdFgDim,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Mono("PAPER", size = 10.sp, color = cp.lcdFgDim, letterSpacing = 1.sp)
                Spacer(Modifier.width(10.dp))
                Mono(model.paper, size = 12.sp, color = cp.lcdFg)
                Spacer(Modifier.weight(1f))
                Mono("ADDR", size = 10.sp, color = cp.lcdFgDim, letterSpacing = 1.sp)
                Spacer(Modifier.width(8.dp))
                Mono(model.address, size = 11.sp, color = cp.lcdFg)
            }

            Mono(model.queueLine, size = 11.sp, color = cp.lcdFgDim)

            model.hint?.let {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Mono("!", size = 12.sp, color = toneColor, weight = FontWeight.Bold)
                    Text(
                        it,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = toneColor,
                    )
                }
            }
        }
    }
}

// ── 二维码 ──────────────────────────────────────────────────────────────────

/** 把一段文字画成二维码。静区（quiet zone）画白，否则扫码器定位不到。 */
@Composable
fun QrCanvas(text: String, modifier: Modifier = Modifier, quiet: Int = 4) {
    val matrix = androidx.compose.runtime.remember(text) {
        runCatching { QrCode.encode(text) }.getOrNull()
    }
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val m = matrix ?: return@Canvas
            val n = m.size
            val cell = size.minDimension / (n + quiet * 2)
            val originX = (size.width - cell * (n + quiet * 2)) / 2f
            val originY = (size.height - cell * (n + quiet * 2)) / 2f
            for (y in 0 until n) {
                for (x in 0 until n) {
                    if (m[x, y]) {
                        drawRect(
                            Color.Black,
                            topLeft = Offset(originX + (x + quiet) * cell, originY + (y + quiet) * cell),
                            size = Size(cell, cell),
                        )
                    }
                }
            }
        }
    }
}

// ── 底部导航 ────────────────────────────────────────────────────────────────

enum class Tab(val label: String) {
    Status("状态"),
    Share("共享"),
    Jobs("任务"),
    Care("维护"),
    Settings("设置"),
}

@Composable
fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(cp.panel)
            .navigationBarsPadding()
            .padding(top = 6.dp, bottom = 6.dp),
    ) {
        HLine(color = cp.panelHair)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Tab.entries.forEach { tab ->
                val on = tab == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(tab) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    NavGlyph(tab, if (on) cp.onPanel else cp.onPanelDim, Modifier.size(22.dp))
                    Text(
                        tab.label,
                        color = if (on) cp.onPanel else cp.onPanelDim,
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/**
 * 手绘导航图标。
 *
 * 不用 material-icons：那一套 24dp 线性图标在深色卡片上判读性差，而且默认的
 * 「齿轮=设置、扳手=维护」和这里的东西（打印机、共享、队列、维护、设置）
 * 对不齐。自己画五条路径，线宽与圆角统一，视觉上更像仪表盘而不是 App 商店。
 */
@Composable
fun NavGlyph(tab: Tab, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val w = 1.7f * u
        val cap = StrokeCap.Round
        when (tab) {
            // 打印机：机身 + 出纸 + 面板灯
            Tab.Status -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(3f * u, 9f * u),
                    size = Size(18f * u, 8.5f * u),
                    cornerRadius = CornerRadius(2f * u),
                    style = Stroke(w),
                )
                drawRoundRect(
                    color = color,
                    topLeft = Offset(7f * u, 4f * u),
                    size = Size(10f * u, 5f * u),
                    cornerRadius = CornerRadius(1.2f * u),
                    style = Stroke(w),
                )
                drawRoundRect(
                    color = color,
                    topLeft = Offset(7.5f * u, 13.5f * u),
                    size = Size(9f * u, 4f * u),
                    cornerRadius = CornerRadius(1f * u),
                    style = Stroke(w * 0.9f),
                )
                drawCircle(color, 1.05f * u, Offset(18f * u, 7.5f * u))
            }
            // 共享：三段弧 + 点
            Tab.Share -> {
                for (r in listOf(9f, 6f, 3f)) {
                    val c = Offset(12f * u, 17f * u)
                    drawArc(
                        color = color,
                        startAngle = 200f,
                        sweepAngle = 140f,
                        useCenter = false,
                        topLeft = Offset(c.x - r * u, c.y - r * u),
                        size = Size(2 * r * u, 2 * r * u),
                        style = Stroke(w, cap = cap),
                    )
                }
                drawCircle(color, 1.4f * u, Offset(12f * u, 19.5f * u))
            }
            // 队列：三条「行」
            Tab.Jobs -> {
                for (i in 0 until 3) {
                    val y = (7f + i * 5f) * u
                    drawCircle(color, 1.1f * u, Offset(4f * u, y))
                    drawLine(color, Offset(8f * u, y), Offset(20f * u, y), w, cap)
                }
            }
            // 维护：三条滑杆
            Tab.Care -> {
                val ys = listOf(7f, 12f, 17f)
                val knobs = listOf(15f, 8f, 17f)
                ys.forEachIndexed { i, y ->
                    drawLine(color, Offset(3f * u, y * u), Offset(21f * u, y * u), w * 0.85f, cap)
                    drawCircle(color, 2.2f * u, Offset(knobs[i] * u, y * u))
                }
            }
            // 设置：齿轮（圆 + 八根齿）
            Tab.Settings -> {
                val c = Offset(12f * u, 12f * u)
                drawCircle(color, 5.6f * u, c, style = Stroke(w))
                drawCircle(color, 2f * u, c)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val dx = Math.cos(a).toFloat()
                    val dy = Math.sin(a).toFloat()
                    drawLine(
                        color,
                        Offset(c.x + dx * 7.2f * u, c.y + dy * 7.2f * u),
                        Offset(c.x + dx * 9.6f * u, c.y + dy * 9.6f * u),
                        w,
                        cap,
                    )
                }
            }
        }
    }
}

/**
 * 空状态。
 *
 * 队列空的时候只丢一张小卡片在顶上，下面半屏全是灰的 —— 屏幕看起来像没加载完。
 * 给一块居中的图形 + 一句话，空白就成了「这里本来就该是空的」，而不是「坏了」。
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    glyph: Tab? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 230.dp)
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (glyph != null) {
            Box(
                Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(if (cp.dark) cp.panelAlt else cp.bg),
                contentAlignment = Alignment.Center,
            ) {
                NavGlyph(glyph, cp.idle, Modifier.size(24.dp))
            }
            Spacer(Modifier.height(14.dp))
        }
        Text(
            title,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            color = cp.onBg,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = cp.onBgDim,
            textAlign = TextAlign.Center,
        )
    }
}
