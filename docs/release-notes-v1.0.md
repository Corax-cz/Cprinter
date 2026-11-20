# Cprinter 1.0

**把一支 USB 打印机变成局域网里的 IPP / AirPrint 打印机，不需要 Root。**

手机 OTG 接上打印机，装上这个 App，同一 WiFi 下的 Windows、Mac、iPhone、iPad
以及别的 Android 手机就都能直接打印了。全程非 Root、不使用 CUPS、不跑 chroot，
PDF 只走系统自带的 `PdfRenderer`。

这是第一个正式版。从 v0.1 到 v1.0，三条入口链路（局域网 IPP、AirPrint、手机自打印）
已经在真机上逐条跑通并出纸。

---

## 本次发布

- 版本号定为 **1.0**（`versionCode 19`），作为首个稳定版。
- 相对 v0.7.9 无代码改动，只是版本号与发布物料归一。
- 补齐 `LICENSE`（GPL-3.0），修正发行说明与仓库元数据。

## 能做什么

| 谁在打 | 怎么连 | 支持格式 |
|---|---|---|
| Windows / macOS / Linux | 添加 IPP 打印机 `ipp://<手机IP>:8631/ipp/print` | PDF、PCL、PWG Raster |
| iPhone / iPad / Mac | AirPrint，打印面板里自动出现设备名 | URF |
| 手机自己（WPS / 相册 / Chrome） | Android 打印框架插件 | PDF |
| 另一台 Android 手机 | 系统打印服务（BIPS）→ mDNS 发现 | PDF、PWG Raster |

- **对外名称可改**：设置 → 对外名称，改完自动重发 mDNS 广播，同时改 IPP 的
  `printer-name`，两边始终一致。
- **测试页**：直接在 App 里打一页自检页，右上角是 App 的 logo 与署名。
- **画质 300 / 600、单面 / 双面**：只对手机自己发起的打印生效；
  网络作业一律以客户端提交的参数为准。
- **37 款机型**（Brother 系机芯，含联想 LJ2600D / HL-2240D，以及一项通用 PCL 直通档）。

## 安装

1. 下载 `Cprinter-v1.0.apk`。
2. 侧载安装（首次需允许「安装未知来源应用」）。
3. 手机 OTG 接打印机 → 打开 App → 按提示授予 USB 权限。
4. 点「启动共享」，界面上会显示可用的 IPP 地址。

覆盖安装会杀掉常驻的共享服务，App 回到前台时会自动拉起来，不需要手动重开。

## 系统要求

- Android 9 及以上（`minSdk 29`，`targetSdk 35`）
- 仅 `arm64-v8a`
- 手机需支持 USB OTG，并有供电足够的 OTG 线

## 已知限制

- **端口是 8631，不是 631**。Android 非 Root 进程绑不上 1024 以下的端口。
- **只支持 Brother 机芯的打印机**。编码核心是 brlaser，机型表见 App 内的机型选择；
  跨品牌的 URF / PWG 会明确拒收，而不是打出一堆废纸。
- **共享期间要保前台服务常驻**。App 已持有 WifiLock 与 WakeLock，但部分定制系统
  （如 ColorOS）仍可能清理后台，建议加入电池优化白名单。
- **缺纸时一个字节都不发**。宁可报错让你去放纸，也不会硬灌导致打印机 USB 端点挂死。
- 打印机的状态只按 USB 打印机类标准位解码（就绪 / 缺纸 / 故障），
  不提供墨粉余量、累计页数这类厂商私有信息。

## 校验

```
文件：Cprinter-v1.0.apk
大小：19,536,562 字节（18.6 MiB）
SHA-256：53c485a390c7a93b8297d817a028045ee8708a18cbd9392094555cdd5814c5c5
```

## 许可证

GPL-3.0。项目链接了 [brlaser](https://github.com/pdewacht/brlaser)（GPL-2.0-or-later）
的编码核心，因此整体按 GPL-3.0 分发。IPP / HTTP 层为手写实现，无第三方依赖。
