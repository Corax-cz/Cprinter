package com.cawzen.printserver

import android.content.Context
import android.util.Log
import com.cawzen.printserver.usb.UsbPrinter

/**
 * Stage 0：USB 发现与端点确认。
 * 这一阶段的唯一目的是回答一个问题——这台设备的 Bulk OUT 端点在哪。
 * 答不上来，整条路径 B 的 USB 前提就不成立，应当立刻停止。
 *
 * 分两种模式，因为设备可能正被别的进程（例如 Termux 里的打印服务）占着：
 *
 * - [Mode.PASSIVE]：只依赖 UsbManager 枚举返回的描述符。不 openDevice、不 claimInterface，
 *   对设备零副作用，不会打断任何正在运行的打印服务。用来拿端点地址。
 * - [Mode.EXCLUSIVE]：openDevice + claimInterface。这会从其它进程手里夺走接口，
 *   必须在确认没有别的服务在用打印机时才能跑。
 */
object Stage0 {

    private const val TAG = "WPrint/Stage0"

    enum class Mode { PASSIVE, EXCLUSIVE }

    data class Outcome(
        val ok: Boolean,
        val report: String,
        val printer: UsbPrinter? = null,
    )

    suspend fun run(context: Context, mode: Mode = Mode.PASSIVE): Outcome {
        val sb = StringBuilder()
        sb.append("=== Stage 0（%s）：USB 发现 ===\n".format(
            if (mode == Mode.PASSIVE) "只读探测，不占用设备" else "独占探测，会 claim 接口"
        ))
        sb.append("已枚举设备：\n").append(UsbPrinter.dumpAllDevices(context)).append("\n\n")

        val device = UsbPrinter.findSupportedPrinter(context)
        if (device == null) {
            val msg = "未找到受支持的打印机（渲染模式只认 Brother / Lenovo 机芯；" +
                "直通模式认任意 USB 打印机）。"
            sb.append(msg).append("\n")
            sb.append("已枚举：\n").append(UsbPrinter.dumpAllDevices(context)).append("\n")
            // 空列表本身不是原因。展开诊断：内核有没有枚举到、/dev/bus/usb 是否可穿越，
            // 这两条能把「没插好」和「系统权限被改坏」区分开。
            sb.append("\n").append(UsbDiagnostics.describe(context))
            Log.e(TAG, msg)
            return Outcome(false, sb.toString())
        }

        val model = UsbPrinter.modelOf(context, device)
        sb.append("匹配到打印机：").append(model?.displayName ?: device.deviceName).append("\n")
        sb.append(UsbPrinter.describe(device)).append("\n")

        // findBulkOut 只读描述符，不需要权限也不需要打开设备。
        val found = UsbPrinter.findBulkOut(device)
        if (found == null) {
            sb.append("未找到 Bulk OUT 端点 —— 路径 B 的 USB 前提不成立，停止。\n")
            Log.e(TAG, "no bulk OUT endpoint")
            return Outcome(false, sb.toString())
        }
        val (iface, ep) = found
        val line = "Bulk OUT endpoint found: 0x%02X (interface=%d maxPacket=%d)".format(
            ep.address, iface.id, ep.maxPacketSize,
        )
        sb.append(line).append("\n")
        Log.i(TAG, line)

        if (mode == Mode.PASSIVE) {
            val hasPerm = UsbPrinter.manager(context).hasPermission(device)
            sb.append("USB 权限：").append(if (hasPerm) "已持有" else "未申请（只读模式不触发弹窗）").append("\n")
            sb.append("\nStage 0（只读）通过：端点已确认，设备未被占用。\n")
            sb.append("下一步若要做独占测试，请先停掉正在使用打印机的其它服务。\n")
            return Outcome(true, sb.toString())
        }

        val granted = UsbPrinter.requestPermission(context, device)
        sb.append("USB 权限：").append(if (granted) "已授权" else "被拒绝").append("\n")
        if (!granted) {
            return Outcome(false, sb.toString())
        }

        val printer = UsbPrinter.open(context, device)
        if (printer == null) {
            sb.append("openDevice / claimInterface 失败。\n")
            sb.append("若另一服务（如 Termux 内的打印服务）正占用该接口，会走到这里 —— 这是预期的保护行为。\n")
            Log.e(TAG, "openDevice or claimInterface failed")
            return Outcome(false, sb.toString())
        }

        sb.append("打印机状态：").append(printer.portStatusText()).append("\n")
        sb.append("\nStage 0（独占）通过。\n")
        return Outcome(true, sb.toString(), printer)
    }
}
