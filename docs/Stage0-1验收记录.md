# Stage 0 / Stage 1 验收记录

记录时间：2026-11-16
设备：Xiaomi Mi 9 SE（`lineage_grus`，LineageOS，Android 15 / SDK 35，kernel 4.9.337，arm64-v8a）
打印机：Lenovo LJ2600D（`17ef:5411`，Brother HL-2240D 同构），经 `1a86:8098` USB Hub 接入
联调方式：无线 adb（无线调试端口每次 adbd 重启会变，用 `adb mdns services` 自动找回）

---

## Stage 0：USB 发现与端点确认

### 验收结果：**通过**

```
设备 /dev/bus/usb/001/055  VID=0x17EF PID=0x5411  接口数=1
  接口[0] id=0 class=0x07 subclass=0x01 端点数=2
    端点 addr=0x01 OUT BULK maxPacket=512
    端点 addr=0x82 IN  BULK maxPacket=512

Bulk OUT endpoint found: 0x01 (interface=0 maxPacket=512)
```

框架侧同一设备：

```
UsbHostManager: Added device UsbDevice[mName=/dev/bus/usb/001/055,mVendorId=6127,
  mProductId=21521,mManufacturerName=Lenovo,mProductName=LJ2600D,...]
  UsbInterface[mId=0,mClass=7,mSubclass=1,mProtocol=2,mEndpoints=[
    UsbEndpoint[mAddress=1,mAttributes=2,mMaxPacketSize=512]
    UsbEndpoint[mAddress=130,mAttributes=2,mMaxPacketSize=512]]]
```

- 接口类 `0x07 / 0x01 / 0x02` = Printer class，双向（有 OUT 也有 IN）。
- 独占模式 `openDevice + claimInterface` 成功，USB 授权弹窗正常走 PendingIntent 流程。
- `PORT_STATUS` 返回 -1：这台机是主机型打印机，不支持标准 class 查询，属预期，不影响打印。

### 阻断问题（已定位并修复）：`/dev/bus/usb` 目录缺执行位

**现象**：`UsbManager.getDeviceList()` 恒为空，连 Hub 都看不到；但 `sysfs` 里
`1-1.1` 稳定存在、`dmesg` 每次枚举都干净成功。

**证据链**：

| 步骤 | 观测 | 结论 |
|---|---|---|
| `dumpsys usb` | `host_manager` 只有 `num_connects`，无任何 `devices` 条目 | 框架从未成功 add |
| `logcat` | `UsbHostManagerJNI: usb_device_open failed`，且总在枚举后 **约 1.06 s** 出现 | 命中 `usb_device_open()` 的 `10 × 100ms` 重试循环，说明 `access()` 一直失败 |
| SELinux | `setenforce 0` 后仍然失败，无 `usb_device` 相关 avc | 排除 SELinux |
| DAC | 节点 `chmod 666` 后仍然失败 | 排除节点权限 |
| 自编探针 `tools/usbprobe` | root 打开成功；`uid=1000 gid=1018` 打开 **EACCES**；同属主普通文件 `0660 root:1018` 对 `uid=1000` 打开成功 | 排除组权限，问题在路径 |
| `ls -ldn /dev/bus/usb` | `drw-rw-rw- 4 0 0 /dev/bus/usb`<br>`drw-rw-rw- 2 0 0 /dev/bus/usb/001` | **目录没有 `x` 位，无法穿越** |

**根因**：`/dev/bus/usb` 与 `/dev/bus/usb/001` 被设成了 `0666`。目录一旦没有执行位，
除 root（`CAP_DAC_OVERRIDE`）之外任何 uid 都无法穿越，system_server 也不例外，
于是框架的 `access()` 空转 1 秒后放弃，设备永远进不了 `mDevices`。

这不是 ROM 默认状态，是后来被改的（首次探测之前就已经是坏的）。**已定位到具体来源**：
本机 Termux 里的打印服务脚本 `printserver/daemon.sh` 第 140 行——

```bash
su -c "chmod -R 666 $DEBIANPATH/dev/bus/usb/" 2>/dev/null
```

`-R` 递归把**目录**也改成了 `0666`。这条命令的本意是让 CUPS 免 root 访问设备节点，实际效果恰好相反：目录失去遍历位后，所有非 root 进程都无法进入，只剩下能绕过 DAC 的 root。这反过来说明为什么那套 Termux 方案必须 root 运行——它自己把免 root 的路堵死了。

**修复**：

```bash
chmod 0755 /dev/bus/usb
chmod 0755 /dev/bus/usb/001
```

修复后立刻 `UsbHostManager: Added device ...`，Stage 0 一次通过。

**这是本机污染，不是 Android 的普遍行为**。AOSP 的 `/system/etc/ueventd.rc`（本机第 70 行）只规定设备节点 `0660 root:usb`，两个目录由 init 以 `0755` 创建。任何未跑过这类脚本的 Android 设备都不存在此问题，本 App 也不需要任何 chmod 或 root。

**根治建议**：把 `daemon.sh:140` 的 `-R` 去掉，只作用于设备节点，目录保持 `0755`，两边就能共存：

```bash
su -c "chmod 666 /dev/bus/usb/*/*" 2>/dev/null   # 不含 -R，不动目录
```

**注意**：该修复是运行时的，重启或再次执行带 `-R` 的脚本后会复发。所以只要那个 Termux 服务还带着这行 `-R`，与本 App 就存在冲突。

---

## Stage 1：Native 渲染核心 + 直写打印

### ① 内部自洽（不碰硬件）：**通过**

同一个 200×200 图案，分别走灰度入口与 1bpp 入口，输出字节完全相同：

```
灰度入口：740 字节  sha256=fa79601e4ad34c3d9859faff0bc85fdd9399bb6636683ec9b9a98c25ff1d7e39
1bpp 入口：740 字节  sha256=fa79601e4ad34c3d9859faff0bc85fdd9399bb6636683ec9b9a98c25ff1d7e39
两条路径：字节完全一致
```

### ② 语义正确性（解码回位图）：**通过**

A4 @300dpi 单面，输出 8045 字节（压缩比 0.7%），sha256
`6bac008955236a226095f978d11fa63ee0785bb8384115828861eff3acde0f1c`。
用 brlaser 自带的 `brdecode` 解回位图后统计：

```
size = 2480 x 3507                 (A4 @300dpi，正确)
black pixels = 553536              (= 744 × 744，一个不多一个不少)
bbox x[868..1611] y[1381..2124]    (期望 左 868 / 上 1381 / 边长 744)
bbox center = (1239, 1752)         (页面中心 1240, 1753)
```

像素级与源图案一致。这条验证**不需要打印机**，可离线复现，是 Stage 1 的常驻回归手段。

### ③ 物理出纸：**通过（用户确认）**

字节已全部写入（`USB 写入 8045 字节，耗时 17 ms`，无 -1 重试），打印机出纸，图案与位置同解码结果一致。

至此 Stage 1 的核心目标达成：**灰度 → Bayer 抖动 → 1bpp → brlaser 1030 压缩 → PCL → USB → 纸面**，整条链路打通，且语义经双向验证（编码后反解回位图逐像素比对 + 物理出纸目视确认）。

### ④ 双面：**通过（用户确认）**

用户确认：**正常翻面，背面上下颠倒**。这正是短边装订（`ESC&l2S`）应有的行为——沿短边翻转后，背面内容相对正面上下颠倒。若打印机打成长边装订（`ESC&l1S`），背面会是左右镜像而非上下颠倒。

> **后续变更（2026-11-16）**：本节的结论建立在「`ESC&l2S` 生效」这个前提上，
> 而该前提后来被推翻 —— iOS 选双面时打出的是**两张单面**。原因是 HL-2240D 的双面
> 单元只有长边翻页，`ESC&l2S` 被机芯静默忽略（不报错、不异常，直接当单面打）。
> 现在 `brcore.cpp` 会把该命令替换为 `ESC&l1S`。
> **因此「背面上下颠倒」不再是判据**，长边装订下背面应当正立。
> 详见 `docs/当前状态.md` §6.1。本文其余部分保持不变，作为当时的实验记录。

单页的 double-sided 作业没有意义——打印机不会为一张纸启动双面器。因此另做了两页探针。

关键结构约束：**两页必须落在同一个 PCL job 内**。brlaser 的 `job` 只在页面参数变化时重发头（`job.cc` 的 `if (!(page_params_ == page_params)) write_page_header()`），页间只有 `1030M\f`。若把两个独立字节流拼接，第二页前会重新出现 128 个 `0x00` + PJL 头 + `ESC E` 复位，打印机会把它当成新作业，双面器不动作。

为此新增了流式多页接口（不触碰既有的 `nativeRender` 签名）：

```
nativeJobBegin(dpi, duplex, paperSize): Long
nativeJobAddPageGray(handle, gray, width, height, stride): Boolean
nativeJobPageCount(handle): Int
nativeJobEnd(handle): ByteArray
nativeJobAbort(handle)
```

做成句柄式而非「一次性传 N 页数组」，是为 Stage 2 预留：整本 PDF 的位图不可能同时驻留内存（A4@600 单页灰度 34MB），每页 encode 完即可释放。

实测结果（A4@300，2 页）：

```
作业内页数 = 2
两页渲染 464 ms，编码 60 ms，输出 104602 字节
ESC&l2S 出现次数 = 1          ← 头只发一次，双面指令在正确位置
PJL EOJ 出现次数 = 1          ← 确证是单个作业，不是两个拼接
USB 写入 104602 字节
```

反解校验两页各自独立（同尺寸、不同内容）：

```
page-1  2480x3507  black=814492  (锚点在右上)
page-2  2480x3507  black=838490  (锚点在左下)
```

纸面标记设计用于判断短边装订（`ESC&l2S`）的翻转方向：第 1 页文字「1 FRONT / 正面」+ 右上角黑块，第 2 页「2 BACK / 反面」+ 左下角黑块。短边装订正确时，翻到背面看第 2 页应为上下颠倒，且黑块落在左上。

至此 **Stage 1 全部通过**：① 内部自洽 ② 语义正确 ③ 出纸 ④ 双面方向。

### 顺带发现：App 无法自行诊断 `/dev/bus/usb` 权限问题

加了 USB 诊断模块后实测，普通 App 对 `/dev/bus/usb` 与 `/sys/bus/usb/devices` **都不可读**——SELinux 的 `untrusted_app` 域限制，与目录本身的 0755 无关。所以：

- 不能凭 `File.canExecute()==false` 判定目录权限坏了，那样会 100% 误报（第一版诊断就犯了这个错）；
- 唯一能确证的判据是「sysfs 确实可读到目标设备，但 UsbManager 为空」——但这在 App 沙箱下通常也读不到；
- 结论：这个问题的排查必须靠 adb，App 只能给出**可能原因清单**与具体修复命令，不能下断言。

诊断输出（正常态）：

```
UsbManager 枚举到设备数：1
  17EF:5411 /dev/bus/usb/001/055
内核 sysfs：App 沙箱无权读取（正常，不代表设备不存在）
结论：目标打印机在 framework 列表中，USB 链路正常。
```

---

## Stage 2：PDF → Bitmap → 渲染核心 → 出纸

用 `android.graphics.pdf.PdfRenderer`，不引入 MuPDF / Poppler。

### 关键约束（实测得出）

**`PdfRenderer.Page.render` 只接受 `ARGB_8888` 的 Bitmap**，`RGB_565` 直接抛 `IllegalArgumentException`。这让内存账变得很紧：

| dpi | 像素尺寸 | 整页 ARGB | 处理方式 |
|---|---|---|---|
| 300 | 2480×3507 | 33.2 MB | 整页渲染 |
| 600 | 4960×7015 | 132.7 MB | **必须条带**（必 OOM） |

阈值定为 64MB：超过就走条带，每带 256 行。

**条带怎么切**：`PdfRenderer` 没有「局部渲染」接口，唯一手段是 `render(bitmap, destClip, transform, mode)` 的组合——Bitmp 只分配 `paperW × 256`，`destClip` 限定绘制区，`transform` 里 `postTranslate(dx, dy - bandStart)` 把该带的页面内容挪到带顶。每带渲染完立刻转灰度并拷进整页灰度缓冲，Bitmap 复用不重分配。

**`eraseColor(Color.WHITE)` 不能省**：PdfRenderer 不保证填充背景，透明区域转灰度后会变黑，整页反相。

### 实测结果

300dpi：

```
整页 ARGB 需 33.2 MB → 整页渲染
页数：1
  第 1/1 页完成（595x842 pt）
编码 514 ms，输出 76887 字节（7.1% of 1bpp 原始大小）
USB 写入 76887 字节，耗时 58 ms
```

600dpi：

```
整页 ARGB 需 132.7 MB → 改走条带渲染（每带 256 行）
  第 1 页：条带渲染完成（每带 256 行）
编码 958 ms，输出 226873 字节（5.2% of 1bpp 原始大小）
JVM 已用 122 MB / 上限 256 MB
USB 写入 226873 字节，耗时 125 ms
```

600dpi 峰值 122MB，约为堆上限的 48%——能跑，但没有余量。构成：整页灰度 34.8MB（Kotlin）+ 34.8MB（JNI `GetByteArrayRegion` 拷贝）+ 4.35MB（1bpp）。要压下去需要改成真正的行式流式喂给编码器（`JobSession` 加 `beginPage/addRows/endPage`），当前不是瓶颈，记为优化项。

### 反解验证

用 `brdecode` 把发出的 600dpi 字节流解回位图：

```
size = 4960 x 7015
bbox x[160..4800] y[158..6856]
bbox center = (2480, 3507)     ← 页心 (2480, 3507.5)，对中
```

解码后的图像经 `pbm2bmp` 转 BMP 目视核对：边距框、标题、三组同心圆（正圆，无椭圆变形）、灰度阶梯 6 档层次分明、8pt/12pt/16pt 三行文字清晰、右上角方向锚在位。600dpi 下线条明显比 300dpi 平滑，且**无条带接缝**——证明 `destClip`/`transform` 的切带拼接正确。

### 一个差点误判的坑：降采样方式

`pbm2bmp` 第一版降采样用「块内任一黑点即判黑」，结果 40%~80% 密度的抖动区域被全部压成纯黑，灰度阶梯看起来只有 3 档——差点被当成半色调缺陷去查 Bayer 矩阵。

正确做法是「块内黑点比例 → 灰度值」。**降采样二值图时，取最大值会把中间调全部吃掉。** 这条对以后任何位图预览都适用。

### 自检 PDF

`print/TestPdf.kt` 用 `PdfDocument` 生成一页矢量 PDF（边框、标题、同心圆、十字、灰度阶梯、字号梯度、方向锚、页脚），写盘后再由 `PdfRenderer` 读回。

用文件而非内存流，是为了让这条路径与将来 IPP 收到的 PDF 完全一致——`PdfDocument` 生成的是真正的矢量 PDF，不是「自己画给自己看」的捷径。

### 状态

渲染与编码侧：**已验证**（反解图像逐项核对）。
物理出纸：**待用户确认**。

---

## 复现工具

| 工具 | 用途 |
|---|---|
| `tools/usbprobe/usbprobe.c` | 以 `uid=1000 / gid=1018` 复现 system_server 的 open 路径，打印 errno。区分 DAC / SELinux / 路径问题 |
| `tools/pbmstat/pbmstat.c` | 统计 P4 位图的黑色包围盒与像素数，比 PowerShell 快两个数量级 |
| `tools/pbm2bmp/pbm2bmp.c` | P4 位图 → 24 位 BMP（可降采样）。让 PC 侧能直接看图核对版面，**不依赖打印机** |
| `reference/brlaser-src/src/brdecode.cc` | 上游解码器，交叉编译到 arm64 后在设备上直接跑 |
| `tools/device.sh` | 连接 / 装包 / 抓日志 / 截屏 |

设备侧重跑「打印 → 反解 → 看图」全流程：

```bash
# 1. 触发打印（结果落在 app 私有目录）
adb shell am start -n com.cawzen.printserver/.MainActivity --es run printpdf
# 2. 反解成 PBM
adb shell 'cd /data/local/tmp && ./brdecode < /data/user/0/com.cawzen.printserver/cache/selftest.pcl'
# 3. 统计 + 转 BMP（降采样倍数取 2~4，太大 ViewImage 读不了）
adb shell '/data/local/tmp/pbmstat /data/local/tmp/page-1.pbm'
adb shell 'cd /data/local/tmp && ./pbm2bmp page-1.pbm p.bmp 4'
adb pull /data/local/tmp/p.bmp
```

`pbm2bmp` 的降采样按**块内黑点比例**映射为灰度；不要改成「任一黑点即判黑」，
否则中间调会被压成纯黑，看起来像半色调坏了。

