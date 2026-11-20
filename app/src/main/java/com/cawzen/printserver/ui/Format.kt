package com.cawzen.printserver.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TIME = SimpleDateFormat("HH:mm:ss", Locale.US)
private val DATE_TIME = SimpleDateFormat("MM-dd HH:mm", Locale.US)
private val DATE = SimpleDateFormat("yyyy-MM-dd", Locale.US)

fun fmtTime(ms: Long): String = if (ms <= 0) "—" else TIME.format(Date(ms))

fun fmtDateTime(ms: Long): String = if (ms <= 0) "—" else DATE_TIME.format(Date(ms))

fun fmtDate(ms: Long): String = if (ms <= 0) "—" else DATE.format(Date(ms))

fun fmtRelative(ms: Long): String {
    if (ms <= 0) return "—"
    val d = System.currentTimeMillis() - ms
    return when {
        d < 0 -> "刚刚"
        d < 60_000 -> "刚刚"
        d < 3600_000 -> "${d / 60_000} 分钟前"
        d < 86_400_000 -> "${d / 3600_000} 小时前"
        else -> "${d / 86_400_000} 天前"
    }
}

fun fmtDuration(ms: Long): String {
    if (ms <= 0) return "0s"
    val s = ms / 1000
    return if (s < 60) "${s}s" else "${s / 60}m${s % 60}s"
}

fun fmtBytes(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "%.1fKB".format(bytes / 1024.0)
    else -> "%.1fMB".format(bytes / 1024.0 / 1024.0)
}
