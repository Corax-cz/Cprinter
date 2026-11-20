package com.cawzen.printserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机恢复共享。
 *
 * 只在**用户上次是开着共享关机的**（`shareEnabled`）才起来 —— 否则一次开机
 * 会凭空弹出一条常驻通知，用户以为 App 自己偷跑。
 *
 * 两个现实约束：
 *
 * - 开机后 USB 权限还没授过，`connectedDevice` 类型的前台服务可能被拒；
 *   [PrintService] 内部会退到 `specialUse`，能起就起，起不来也不影响用户手动开。
 * - Android 14+ 对「从 BOOT_COMPLETED 启动前台服务」有类型限制。被系统拒绝时
 *   这里只记日志，不弹任何提示 —— 开机场景下弹窗既打扰又没用，用户回桌面
 *   点一下图标就好。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // `MY_PACKAGE_REPLACED`：覆盖安装新 APK 时系统会把前台服务一起杀掉。
        // 这是实测中最容易复现「打印机突然搜不到」的场合 —— 每次装完新版都得手动
        // 回 App 点一下「启动共享」。覆盖安装不属于「开机自启」的范畴，所以这条
        // 不看 `autoStartOnBoot`，只看用户是不是想让共享开着。
        val replaced = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!replaced &&
            intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        val cfg = AppSettings.load(context)
        if (!cfg.shareEnabled) {
            Log.i("WPrint/Boot", "共享未开启，跳过")
            return
        }
        if (!replaced && !cfg.autoStartOnBoot) {
            Log.i("WPrint/Boot", "开机自启未开启，跳过")
            return
        }
        runCatching { PrintService.start(context) }
            .onFailure { Log.w("WPrint/Boot", "启动共享失败：${it.message}") }
    }
}
