package com.cawzen.printserver

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.cawzen.printserver.usb.UsbPrinter
import java.io.File

/**
 * 枚举为空时的自诊断。
 *
 * 为什么需要这一层：`UsbManager.getDeviceList()` 返回空是一个「终点」，不是「原因」。
 * 至少三种成因会让它返回空，而界面上它们长得一模一样：
 *
 *   1. 没插设备 / OTG 没开 / 线是充电线
 *   2. 系统的 /dev/bus/usb 目录执行位被去掉（本次真机调试踩的就是这个：
 *      某脚本 `chmod -R 666 /dev/bus/usb` 把目录的 x 位也删了，非 root 一律 EACCES，
 *      system_server 空转 10×100ms 后放弃，所有 App 都看不到打印机）
 *   3. ROM 的 USB Host 服务没收到 uevent
 *
 * **重要限制（实测得出，别照直觉写）**：App 自己无法可靠地探测第 2 种。
 * SELinux 把普通 App 放进 untrusted_app 域，它对 /dev/bus/usb 和 /sys/bus/usb/devices
 * 都没有访问权 —— 即使目录是 0755，`File.canExecute()` 也返回 false，`listFiles()`
 * 也拿不到内容。所以：
 *
 *   - 不能凭 `canExecute()==false` 断定目录权限坏了，那会 100% 误报；
 *   - 只有 sysfs **确实可读**且看到了目标设备、而 UsbManager 为空时，才能确证
 *     「内核有、框架无」，这也才是唯一有指向性的判据；
 *   - 其余情况只给可能原因清单，不下断言。
 *
 * 全部是只读操作，不申请权限、不打开设备、不改状态。
 */
object UsbDiagnostics {

    private const val TAG = "WPrint/Diag"

    fun describe(context: Context): String {
        val sb = StringBuilder()
        sb.append("── USB 诊断（只读）──\n")

        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            sb.append("系统声明支持 USB Host：否\n")
            sb.append("该 ROM 未声明 android.hardware.usb.host，任何 App 都无法作为 USB 主机。\n")
            sb.append("这是系统能力缺失，与本 App 无关。\n")
            return sb.toString()
        }
        sb.append("系统声明支持 USB Host：是\n")

        val devices = UsbPrinter.allDevices(context)
        sb.append("UsbManager 枚举到设备数：").append(devices.size).append("\n")
        for (d in devices) {
            sb.append("  %04X:%04X %s\n".format(d.vendorId, d.productId, d.deviceName))
        }

        // sysfs 探测的结果要标注可信度：读到才算证据，读不到不是「没有设备」。
        val kernel = readKernelDevices()
        when {
            kernel == null -> sb.append("内核 sysfs：App 沙箱无权读取（正常，不代表设备不存在）\n")
            kernel.isEmpty() -> sb.append("内核 sysfs：可读，但当前没有任何 USB 设备\n")
            else -> {
                sb.append("内核 sysfs：可读到 ").append(kernel.size).append(" 个设备\n")
                for (d in kernel) {
                    sb.append("  ").append(d.node).append("  ")
                        .append(d.vid).append(":").append(d.pid)
                    if (d.isTarget) sb.append("  ← 目标打印机")
                    sb.append("\n")
                }
            }
        }

        appendConclusion(sb, devices, kernel)
        return sb.toString()
    }

    private data class KernelDevice(val node: String, val vid: String, val pid: String) {
        val isTarget: Boolean
            get() = vid.equals("%04x".format(UsbPrinter.VENDOR_ID), ignoreCase = true) &&
                pid.equals("%04x".format(UsbPrinter.PRODUCT_ID), ignoreCase = true)
    }

    /** 返回 null 表示「读不到」（沙箱限制），返回空列表表示「读到了，确实没有设备」。 */
    private fun readKernelDevices(): List<KernelDevice>? {
        val entries = runCatching { File("/sys/bus/usb/devices").listFiles() }.getOrNull()
        if (entries == null) return null
        val result = ArrayList<KernelDevice>()
        for (entry in entries) {
            // 只认设备节点（有 idVendor），跳过 1-1:1.0 这类接口目录
            val vid = runCatching { File(entry, "idVendor").readText().trim() }.getOrNull()
                ?: continue
            val pid = runCatching { File(entry, "idProduct").readText().trim() }.getOrNull()
                ?: continue
            result.add(KernelDevice(entry.name, vid, pid))
        }
        return result
    }

    private fun appendConclusion(
        sb: StringBuilder,
        devices: List<android.hardware.usb.UsbDevice>,
        kernel: List<KernelDevice>?,
    ) {
        sb.append("\n结论：")
        val targetInFramework = devices.any {
            it.vendorId == UsbPrinter.VENDOR_ID && it.productId == UsbPrinter.PRODUCT_ID
        }
        val targetInKernel = kernel?.any { it.isTarget } == true

        when {
            targetInFramework -> {
                sb.append("目标打印机在 framework 列表中，USB 链路正常。\n")
            }
            targetInKernel -> {
                // 唯一能确证的判据：App 亲眼在内核里看到了它，但框架没给出来。
                sb.append("内核已枚举到目标打印机，但 framework 层看不到。\n")
                sb.append("  最常见的成因是 /dev/bus/usb 的目录执行位被去掉了 —— 目录没有 x 位就无法穿越，\n")
                sb.append("  非 root 进程（包括 system_server）一律 EACCES。常见于跑过需要 root 的\n")
                sb.append("  USB 工具、或执行过 `chmod -R 666 /dev/bus/usb` 这类命令之后。\n")
                sb.append("  用 adb 确认并修复：\n")
                sb.append("    adb shell ls -ld /dev/bus/usb /dev/bus/usb/*\n")
                sb.append("    # 若显示 drw-rw-rw-（没有 x），执行：\n")
                sb.append("    adb shell su -c \"chmod 0755 /dev/bus/usb /dev/bus/usb/*\"\n")
                Log.e(TAG, "target visible in sysfs but not in framework")
            }
            devices.isNotEmpty() -> {
                sb.append("framework 能看到 USB 设备，但其中没有目标打印机。\n")
                sb.append("  目标应为 %04X:%04X，请确认机型是否匹配。\n".format(
                    UsbPrinter.VENDOR_ID, UsbPrinter.PRODUCT_ID,
                ))
            }
            else -> {
                sb.append("framework 没有枚举到任何 USB 设备。按可能性排查：\n")
                sb.append("  1. 打印机电源是否打开、是否已从休眠唤醒；\n")
                sb.append("  2. USB 线是数据线还是纯充电线（后者不带数据对）；\n")
                sb.append("  3. OTG / 转接头是否接触良好，换个口试试；\n")
                sb.append("  4. 若是插在 USB Hub 上，Hub 是否供电充足；\n")
                sb.append("  5. 若手机上跑过需要 root 的 USB 工具，可能是 /dev/bus/usb 目录权限被改坏 ——\n")
                sb.append("     用 adb 执行 `ls -ld /dev/bus/usb /dev/bus/usb/*` 看一眼，\n")
                sb.append("     正常应为 drwxr-xr-x，若是 drw-rw-rw- 则执行\n")
                sb.append("     `su -c \"chmod 0755 /dev/bus/usb /dev/bus/usb/*\"`。\n")
                sb.append("  （App 自身无权执行这个修复，也无权读取上述目录的权限位。）\n")
            }
        }
    }
}
