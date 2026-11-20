package com.cawzen.printserver.usb

import android.content.Context
import com.cawzen.printserver.Stage0
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「现在这台打印机到底什么状态」的**按需**读取。
 *
 * 为什么不做成定时轮询：USB 打印机只有一个接口，读状态要 `openDevice` +
 * `claimInterface` —— 和正在打印的作业是互斥的。定时轮询会在作业写到一半时
 * 抢走接口，结果是作业失败 + 「打印机不可用」。所以只在用户主动刷新、诊断、
 * 或者作业开始前读一次，并且用 [UsbGate] 的 `tryLock` 拿到锁才读，拿不到就
 * 老实说「正在打印，跳过」。
 */
object PrinterProbe {

    data class Snapshot(
        val ok: Boolean,
        /** 原始状态字节；-1 = 读不到。 */
        val raw: Int = -1,
        val text: String = "",
        val paperEmpty: Boolean = false,
        val error: Boolean = false,
        val offline: Boolean = false,
        val at: Long = 0L,
        /** 失败/跳过的原因（人话）。 */
        val note: String = "",
    )

    suspend fun read(context: Context): Snapshot = withContext(Dispatchers.IO) {
        val device = UsbPrinter.findSupportedPrinter(context)
            ?: return@withContext Snapshot(false, note = "没有检测到受支持的打印机")
        if (!UsbPrinter.manager(context).hasPermission(device)) {
            return@withContext Snapshot(false, note = "还没有 USB 访问权限")
        }
        val locked = runCatching { UsbGate.lock.tryLock(150, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        if (!locked) return@withContext Snapshot(false, note = "正在打印，暂时不读状态")

        try {
            val opened = Stage0.run(context, Stage0.Mode.EXCLUSIVE)
            val p = opened.printer
                ?: return@withContext Snapshot(false, note = "无法打开打印机（接口被其它进程占用？）")
            try {
                val s = p.portStatus()
                if (s < 0) {
                    return@withContext Snapshot(
                        false, raw = -1, at = System.currentTimeMillis(),
                        note = "打印机没有回应状态查询（休眠 / 未开机 / 不支持 GET_PORT_STATUS）",
                    )
                }
                // 解码只在 UsbPrinter.portState() 里做，这里不重复位运算 ——
                // 上一版这里也写了一遍 `s and 0x08`，把 nFault 当成了缺纸。
                val ps = p.portState()
                Snapshot(
                    ok = true,
                    raw = s,
                    text = p.portStatusText(),
                    paperEmpty = ps?.paperEmpty == true,
                    error = ps?.fault == true,
                    offline = ps?.offline == true,
                    at = System.currentTimeMillis(),
                )
            } finally {
                runCatching { p.close() }
            }
        } finally {
            runCatching { UsbGate.lock.unlock() }
        }
    }
}
