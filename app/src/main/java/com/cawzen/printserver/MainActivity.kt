package com.cawzen.printserver

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.cawzen.printserver.ui.AutoResumeShare
import com.cawzen.printserver.ui.BottomBar
import com.cawzen.printserver.ui.CprinterTheme
import com.cawzen.printserver.ui.JobsScreen
import com.cawzen.printserver.ui.MaintenanceScreen
import com.cawzen.printserver.ui.SettingsScreen
import com.cawzen.printserver.ui.ShareScreen
import com.cawzen.printserver.ui.StatusScreen
import com.cawzen.printserver.ui.Tab
import com.cawzen.printserver.ui.UiController
import com.cawzen.printserver.ui.Wizard
import com.cawzen.printserver.ui.cp
import com.cawzen.printserver.ui.rememberRuntime
import com.cawzen.printserver.usb.UsbPrinter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 13+ 的常驻通知需要运行时权限。被拒不影响服务，只是通知栏看不到。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0x5750)
            }
        }

        setContent {
            CprinterTheme {
                AppRoot()
            }
        }
    }

    /**
     * 每次回到前台都确认一次共享还活着。
     *
     * 这是最便宜、命中率最高的自愈点：ROM 把前台服务杀掉之后，用户「打开 App 看看」
     * 是必然发生的动作。没有这一步，用户看到的永远是「共享明明开着，但打印机从
     * 手机和电脑的列表里消失了」—— 因为 `_ipp._tcp` 的广播随进程一起没了。
     *
     * 只补「用户意图是开着」的情况（`shareEnabled`）。用户主动点过「暂停共享」的，
     * 不能在这里偷偷开回来。
     */
    override fun onResume() {
        super.onResume()
        val cfg = AppSettings.load(this)
        if (cfg.shareEnabled && cfg.wizardDone && PrintService.instance == null) {
            runCatching { PrintService.start(this) }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val ctl = UiController.remember()
    val rt = rememberRuntime()
    var options by remember { mutableStateOf(PrintOptions.load(context)) }
    var tab by remember { mutableStateOf(Tab.Status) }
    val snackbar = remember { SnackbarHostState() }

    // 反馈统一走 Snackbar：短暂、不打断，且不会在界面里留一块会过期的卡片。
    LaunchedEffect(ctl.toast) {
        val msg = ctl.toast
        if (msg != null) {
            snackbar.showSnackbar(msg)
            ctl.consumeToast()
        }
    }

    // 上次是开着共享关掉的 App → 现在补起来（用户不需要记得点按钮）。
    AutoResumeShare(ctl, rt)

    // 启动就把 USB 权限拿好：打印框架插件在后台收作业时弹不出权限框。
    LaunchedEffect(Unit) {
        val d = UsbPrinter.findSupportedPrinter(context) ?: return@LaunchedEffect
        if (!UsbPrinter.manager(context).hasPermission(d)) {
            UsbPrinter.requestPermission(context, d)
        }
    }

    // 无人值守触发，供联调脚本使用：am start --es run printpdf / server / stopserver。
    // 保留这条路径是有意的 —— 没有它，每次验证都要手动点屏幕，真机排查会很慢。
    val autoRun = remember {
        (context as? android.app.Activity)?.intent?.getStringExtra("run")
    }
    LaunchedEffect(autoRun) {
        when (autoRun) {
            "printpdf" -> ctl.printTestPage()
            "printpdf300" -> ctl.printTestPage(300)
            "printpdf600" -> ctl.printTestPage(600)
            "server" -> ctl.startShare()
            "stopserver" -> ctl.stopShare()
        }
    }

    val inWizard = !ctl.cfg.wizardDone

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = cp.bg,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!inWizard) {
                BottomBar(tab) { tab = it }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            if (inWizard) {
                Wizard(rt, ctl) { }
            } else {
                when (tab) {
                    Tab.Status -> StatusScreen(rt, ctl) { tab = it }
                    Tab.Share -> ShareScreen(rt, ctl)
                    Tab.Jobs -> JobsScreen(rt, ctl)
                    Tab.Care -> MaintenanceScreen(rt, ctl)
                    Tab.Settings -> SettingsScreen(
                        rt = rt,
                        ctl = ctl,
                        options = options,
                        onOptions = { options = it },
                        onRerunWizard = { ctl.restartWizard() },
                    )
                }
            }
        }
    }
}
