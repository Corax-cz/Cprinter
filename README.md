Cprinter，把一部闲置 Android 手机变成 USB 打印机的无线 IPP/AirPrint 服务器。
不需要 Root，不需要 CUPS，不需要在手机上跑 Linux。
Windows、macOS、iOS、Android 都能把它当网络打印机用。
（目前适用范围：1、支持原生渲染与 PCL/PostScript 直通的打印机。2、brlaser 覆盖的是 Brother 单色激光机芯及其贴牌机型。）

已实现验证：把联想 LJ2600D（Brother HL-2240D 机芯）通过 Android 手机的 USB OTG 变成局域网 IPP 打印机。

四层：IPP 服务器 → PDF 光栅化 → Native 编码核心（brlaser）→ USB Bulk 传输。

手机打印、AirPrint均已在真机验证。

三个方式打印：
1.局域网 IPP 服务
2.iPhone / iPad / Mac：AirPrint
3.软件内上传打印

## 0. 怎么用

1. 手机 OTG 接打印机，装 APK，打开 App 按引导进行连接。
2. 点「启动 IPP 服务」，记下界面上的地址。
3. 电脑设置内直接添加打印机，或通过地址添加。iphone点击打印后直接选择对应的打印机即可。

底部的「画质 300/600」「单面/双面」**只对手机自己发起的打印生效**
（「系统打印（手机）」和「测试页」），选择会持久化。
网络作业（IPP / AirPrint）的分辨率、份数、单双面**一律以客户端提交的为准**，
不继承界面 —— 原因见 `docs/当前状态.md` §6.1。
Windows 厂商驱动走的是 PCL 直通，画质由 Windows 的驱动设置决定。

**iPhone / iPad / Mac**：不用配置。手机与设备在同一 WiFi 下，打印面板里就会出现
「Cprinter」（AirPrint 走 `_universal` 子类型 + URF，见 `docs/AirPrint.md`）。
份数、单双面、分辨率都由 iOS 自己决定并提交，我们照办。
机芯的光栅只有 600dpi 一档，客户端送 300 会先重采样到 600 再编码。

---

## 1. 目录

```
app/src/main/cpp/brlaser/     brlaser 编码核心（上游原样，GPL-2.0-or-later）
app/src/main/cpp/brcore.cpp   抖动 + 编码（JNI 无关，hostref 共用同一份）
app/src/main/cpp/bridge.cpp   仅 JNI 参数搬运
app/src/main/java/.../usb/    USB 发现、权限、分块传输（L1）
app/src/main/java/.../print/  JNI 声明、PDF 渲染、PCL 直通、PWG/URF 解码、文档嗅探
app/src/main/java/.../ipp/    IPP 编解码 + HTTP 服务器 + 作业队列（Stage 3）
app/src/main/java/.../androidprint/  Android 打印框架插件（手机直接打印）
app/src/main/java/.../usb/UsbGate.kt 跨组件 USB 串行锁（IPP 与打印插件共用）
app/src/main/java/.../mdns/   手写 mDNS 响应器（AirPrint 的 _universal 子类型）+ TXT 定义
app/src/main/java/.../net/    对外地址选择（避开 VPN / 蜂窝，只认 WiFi 网段）
app/src/main/java/.../PrintService.kt  前台服务（常驻 IPP 服务器）
app/src/main/java/.../PrintOptions.kt  画质/单双面设置（界面与 IPP 共用）
app/src/main/java/.../Stage0.kt  USB 端点确认
app/src/main/java/.../Stage1.kt  字节自洽 + 物理出纸
tools/ipptest/                IPP 协议探针（Python，标准库，无依赖）+ PWG/URF 样本生成器 + mDNS 查询器
tools/adb.sh                  无线 adb 封装（自动 mDNS 发现 + 重连）
testdata/reference/           hostref 产出的参考输出
testdata/stage3/              IPP 验收样本（PDF / PCL / PWG Raster）
testdata/stage4/              AirPrint 验收样本（URF 8bpp / 24bpp / 双面两页）
docs/                         可行性评估、风险记录、各阶段验收记录、当前状态快照
docs/开发日志.md               按版本记的「为什么」与排查手册（踩坑根因都在这）
backup/                       各版本源码 + APK 快照（zip）
toolchain/                    本机工具链（JDK / Android SDK / NDK），非项目产物
reference/brlaser-src/        上游源码快照（核查证据）
```

## 2. 环境

全部装在 `toolchain/` 下，不污染系统：

| 组件 | 路径 | 版本 |
|---|---|---|
| JDK | `toolchain/jdk` | Microsoft OpenJDK 17 |
| Android SDK | `toolchain/android-sdk` | platform 35 / build-tools 35.0.0 |
| platform-tools | `toolchain/android-sdk/platform-tools` | 含 adb |
| NDK | `toolchain/android-sdk/ndk` | 27.0.12077973 |
| Gradle | wrapper 自动下载 | 8.13 |

构建：

```bash
./build.sh            # 自动处理路径转换并调用 gradlew
```

### 构建路径

项目位于含中文的目录，这会撞上两个 Windows 工具链缺陷，实测结论：

| 现象 | 原因 | 处理 |
|---|---|---|
| `Your project path contains non-ASCII characters` | AGP 主动拒绝非 ASCII 路径 | 已开 `android.overridePathCheck`，但单靠它不够 |
| CMake 退出码 `-1073740791`（0xC0000409），日志停在 `CXX compiler identification` | CMake 在中文路径下 UTF-8↔GBK 转换崩溃，3.22.1 与 3.31.6 均复现 | 用 `subst W:` 把项目映射成 ASCII 路径构建 |

所以标准做法是：**从 `W:\` 构建**（`./build.sh` 会自动建立该映射）。
物理文件始终在 `D:\workbody\无线打印安卓客户端`，`W:` 只是一个盘符别名，
重启后消失，`build.sh` 会重建。

`local.properties` 里的 `sdk.dir` 也因此指向 `W:\toolchain\android-sdk`。

安装：

```bash
./toolchain/android-sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

## 3. 验收记录

| 项 | 判据 | 状态 |
|---|---|---|
| Stage 0 | Logcat 出现 `Bulk OUT endpoint found: 0xXX` | **已通过**（真机 `0x01`，详见 `docs/Stage0-1验收记录.md`） |
| Stage 1-① | 灰度入口与 1bpp 入口输出字节一致 | **已通过**（sha256 相同） |
| Stage 1-② | 输出流反解回位图与源图案逐像素一致 | **已通过**（`brdecode` + `pbmstat`，744×744 余量零误差） |
| Stage 1-③ | 打印机出纸，图案位置/比例正确 | **已通过**（用户确认出纸，图案与位置正确） |
| Stage 1-④ | 双面模式方向正确 | **已通过**。原始实现用 `ESC&l2S`（短边）；2026-11-16 确认该命令在 HL-2240D 上被静默忽略，已改为 `ESC&l1S`（长边） |
| Stage 2 | PDF 出纸，峰值内存受控 | **已通过**：300dpi 整页 / 600dpi 条带均出纸；用户目视确认 600dpi 明显更细腻，**已定为默认画质**，300/600 可在界面切换 |
| Stage 3-① | IPP 属性查询与作业提交 | **已通过**：Get-Printer-Attributes / Validate-Job / Print-Job / Get-Job-Attributes 全通（`tools/ipptest`） |
| Stage 3-② | 局域网经 IPP 真打 | **已通过**：600dpi PDF → 226873 字节 → USB 180ms → `job-state=9` |
| Stage 3-③ | PCL 直通（Windows 厂商驱动路径） | **已通过**：8045 字节原样透传，1ms 完成 |
| Stage 3-④ | 前台服务 + mDNS | **已通过**：`_ipp._tcp` 注册成功，服务常驻 |
| Stage 3-⑤ | Windows 手动添加并按测试页 | **已通过**：本机新建队列 `\\http://192.168.123.60:8631\WPrint-LJ2600D`，打印测试页出纸（PCL 161812 字节，USB 76ms）。`WPrint-LJ2600D` 是当时的默认名，v1.0 起默认名为 `Cprinter` |
| Stage 3-⑥ | PWG Raster（无厂商驱动路径） | **已通过**：单页/两页/双面三种形态，解码结果与生成器 CRC32 逐字节一致（详见 §4） |
| Stage 3-⑦ | 锁屏与 Doze 存活 | **已通过**：`force-idle` 深度 Doze 下仍正常收作业并出纸 |
| Stage 4-① | mDNS 带 `_universal` 子类型 | **已通过**：从 PC 查询子类型 PTR，收到 PTR+SRV+TXT+A 完整应答（515 字节），压缩指针与 cache-flush 位正确 |
| Stage 4-② | URF 解码（8bpp / 24bpp / 双面两页） | **已通过**：三种样本的灰度 CRC32 与生成器逐字节一致（`FC1D398D` / `B76FDAA1`） |
| Stage 4-③ | iOS 的 Create-Job → Send-Document → Close-Job | **已通过**：`job-state` 3→5→9，`job-impressions=2`，USB 95098 字节 |
| Stage 4-④ | iPhone 真机出纸 | **已通过**（2026-11-16：相册照片 334066B URF → 28812B PCL → 出纸） |
| Stage 4-⑤ | iOS 选「双面」 | **已通过**：URF 页头 `duplex=3` → 长边双面，一张纸正反都有内容 |
| Stage 4-⑥ | iOS 选份数 | **已通过**：3 份 × 2 页 × 双面 = 3 张双面纸（PCL `ESC&l3X`，字节数不随份数增长） |

### 手机打印（打印框架插件）

| 项 | 结果 |
|---|---|
| 系统注册打印服务 | **已通过**：`dumpsys print` 的 `installed_services` 含 `UsbPrintPlugin` |
| 打印机被发现 | **已通过**：`printers` 段 `name=联想 LJ2600D status=1` |
| 打印界面可见 | **已通过**：下拉列表显示「联想 LJ2600D / USB 直连 · 无需驱动 · 黑白激光」 |
| 能力声明生效 | **已通过**：选项面板显示 颜色=黑白、方向=纵向、双面、A4 |
| 选中后预览正常 | **已通过**：出现打印机图标，预览无异常 |
| 点打印 → 出纸 | **已通过**：88652 字节 PDF → 条带渲染 → 225182 字节 → USB 150ms → `state=5 completed` |

已完成的机械性验证：

- `./build.sh` 成功产出 `app/build/outputs/apk/debug/app-debug.apk`（19.5 MB）
- APK 内含 `lib/arm64-v8a/libwprint.so`（475 KB，已 strip）
- `.so` 导出 9 个 JNI 符号：`nativeRender` / `nativeRender1bpp` / `nativePack1bpp` /
  `nativeTestPatternGray` / `nativeJobBegin` / `nativeJobAddPageGray` /
  `nativeJobEnd` / `nativeJobPageCount` / `nativeJobAbort`
- `aapt2 dump badging`：package `com.cawzen.directprint`，label `Cprinter`，native-code `arm64-v8a`

## 4. 风险清单

| 风险 | 处理 | 状态 |
|---|---|---|
| brlaser 剥离不彻底 | 编码核心原样编译，仅 `next_line()` 换了数据源；`brdecode` 反解逐像素验证通过 | 已验证 |
| **`/dev/bus/usb` 目录缺执行位** | 目录被设成 `0666`，非 root 无法穿越，框架枚举全废。已定位到具体来源：本机 Termux `printserver/daemon.sh:140` 的 `chmod -R 666 /dev/bus/usb`。`chmod 0755` 修复。**这是本机污染，不是 Android 普遍行为**，AOSP 目录默认 `0755`，本 App 不需要任何 chmod/root | 已定位已修复；根治需改那个脚本（去掉 `-R`） |
| bulkTransfer > 16384 | `UsbPrinter.CHUNK_SIZE = 16384`，且按 `sent` 推进而非按 `len` | 已处理，出纸验证通过 |
| bulkTransfer 返回 -1 | 退避重试 3 次（500/1000/1500ms），仍失败抛 `IOException` | 已处理，未触发 |
| PdfRenderer 透明背景 | `eraseColor(WHITE)` 已加（`PdfPrint.renderPageToGray`） | 已处理 |
| **PdfRenderer 只收 ARGB_8888** | A4@600 整页要 132.7MB，`RGB_565` 会抛异常。改为超 64MB 自动条带（每带 256 行，`destClip` + `transform` 切） | 已处理，实测可跑 |
| 600dpi 峰值内存偏高 | 实测 122MB / 256MB。构成：灰度 34.8 + JNI 拷贝 34.8 + 1bpp 4.3。可改行式流式喂入压到 ~10MB | 已知，非瓶颈 |
| **选了双面却出两张单面** | 三层原因：①上游 `ESC&l2S` 是短边，机芯只有长边翻页、静默降级单面 → 在 `brcore.cpp` 的 `finish()` 里换成 `ESC&l1S`；②网络作业继承了界面的「单面」→ 改 `fromClient ?: false`；③iOS 的双面意图只在 URF 页头，IPP `sides` 根本不发 → `RasterPage.duplexHint` | 已解决，真机确认一张纸双面 |
| **App 无法自行诊断 USB 权限问题** | 实测 `untrusted_app` 域对 `/dev/bus/usb` 与 sysfs 均不可读，凭 `canExecute()==false` 判定会 100% 误报。诊断改为只给可能原因清单 + 修复命令 | 已知限制 |
| mDNS 组播不可达 | 自实现响应器（5353 + MulticastLock），失败时降级回 `NsdManager` 并在界面标注「AirPrint 不可用」 | 已实现；Win「公用网络」防火墙会挡单播应答，用组播验证 |
| 加密/复杂 PDF | PdfRenderer 不覆盖，列为已知限制 | 已知限制 |
| 600dpi 整页 OOM | 改为条带渲染（每带 256 行） | 已处理，实测 122MB/256MB |
| AirPrint 入口是**子类型**而非 TXT | `NsdManager` 无子类型 API，改用自实现 mDNS 响应器发 `_universal._sub._ipp._tcp` PTR | 已解决 |
| iOS 走 Create-Job/Send-Document | `JobStore` 增加「作业已建、文档未到」中间态，不进队列不算 queued | 已解决 |
| URF 的 PackBits 与标准**反向**，`0x80` 是「本行余白」 | 三个独立实现交叉核对后按 URF 语义实现，CRC 对账 | 已解决 |
| **VPN 会偷走对外地址** | 开 Tailscale/Clash 时 VPN 网络也带 WIFI 传输类型，会选中 `tun0` + `100.64/10`。`NetInfo` 跳过 VPN 网络并优先站点内网地址 | 已解决 |
| **端口不能是 631** | 非 Root 应用无法 bind < 1024。改用 8631，并在 Windows 侧改端口 URL | 已处理，需用户改一次 |
| **Windows IPP Class Driver 送 PWG Raster** | 已实现 PWG Raster 解码（无压缩，sGray 8bpp / sRGB 24bpp / 1bpp），解码结果用 CRC32 与生成器对账 | 已解决 |
| **改了 C++ 但 APK 里还是旧库** | `./build.sh` 只跑 `assembleDebug` 时不一定会重新编译 native（`packageDebug` 报 UP-TO-DATE）。改 `.cpp` 后必须 `./build.sh :app:externalNativeBuildDebug`；确认靠 `.so` 里搜特征字节，不看 mtime | 已知，写进 `docs/当前状态.md` §8 |

## 5. 已知限制

- 只构建 `arm64-v8a`。其它 ABI 需要时在 `app/build.gradle.kts` 放开。
- AirPrint 已支持（URF + `_universal` 子类型），但**没有 AAAA 记录**：纯 IPv6 局域网里
  iOS 找不到本机。另外没做名字冲突探测，局域网里若已有同名 `Cprinter` 会打架。
- **IPP 端口固定 8631**：`bind()` 到 1024 以下需要 `CAP_NET_BIND_SERVICE`，非 Root 拿不到。
  服务器接受任意 POST 路径，所以 Windows 侧只改端口号、保留旧路径也能工作。
- 走 PCL 直通时，App 里的「画质 / 单双面」不生效——渲染方是 Windows 的驱动。
- 作业状态不持久化：进程重启后 job-id 从 1 重新计数。
- `hostref` 编译为 Android arm64 可执行文件在手机上跑，而非 Windows 主机版：
  NDK 的 clang 在 Windows 上需要 MSVC CRT 才能产出 .exe。它的独立性来自"不经过 JNI"，
  跑在手机上同样成立。

## 6. 复现 Stage 1 测试用例

1. 装 APK，接打印机，点「Stage 0」→ 记下端点地址与状态字。
2. 点「字节自洽」→ 记录 `sha256` 与两个入口的字节数，产物在
   `/data/data/com.cawzen.directprint/files/stage1/`。
3. 跑 `tools/hostref/build_hostref.sh` 编译参考实现，`tools/hostref/run_on_device.sh`
   在手机上生成参考输出并拉回 `testdata/reference/`。
4. 比对：

```bash
adb pull /data/data/com.cawzen.directprint/files/stage1/pattern_200x200_stream.bin /tmp/apk.bin
cmp -l /tmp/apk.bin testdata/reference/pattern_200x200_stream.bin | head
```

`sizes 相同 + sha256 相同` 即通过；有差异时 `cmp -l` 的输出第一行就是第一个差异字节的偏移。

## 7. 许可证

- `app/src/main/cpp/brlaser/`：brlaser，**GPL-2.0-or-later**（© 2013 Peter De Wachter）。
- 本项目其余部分：**GPL-3.0-or-later**，包括手写的 IPP/HTTP 服务器
  （`ipp/`、`mdns/`、`PrintService.kt`）——没有引入 google/virtual-printer 或任何
  Apache-2.0 组件，因此不存在多许可证混用的解释成本。

**因此：本 APK 一旦分发，必须按 GPL-3.0 提供完整源码。**
