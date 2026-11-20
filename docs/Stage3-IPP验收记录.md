# Stage 3 验收记录：IPP 服务器 + mDNS 广播

日期：2026-11-16
设备：Mi 9 SE（Android 11）· 联想 LJ2600D（VID `0x17EF` / PID `0x5411`）· 无线 adb 联调
服务地址：`ipp://192.168.123.60:8631/ipp/print`

---

## 1. 做了什么（与计划的偏差）

计划文档里写的是「Fork google/virtual-printer（Apache-2.0），只重写 `DocumentProcessor`」。
实际实现改成了**手写 IPP/HTTP 服务器**，原因有三条，都是实施时才看清的：

1. **依赖收益为零。** 那个工程的价值在 Ktor/Netty + JIPP 属性模型；而我们需要的能力
   只有「读一个 POST body，回一个 POST body」加上二十来个属性。引进来反而要处理
   keep-alive、最大 body、字符集这些额外参数。
2. **它对文档格式的假设和我们不同。** 它是给「随便什么格式都转 PDF」的场景写的，
   而我们恰好相反：**PDF 才是要被渲染的那一端**，PCL 反而应当原样透传。
3. **许可证更干净。** 不再有 Apache-2.0 依赖，整体可以只受 brlaser 的 GPL 约束。

新增代码结构：

```
app/src/main/java/.../ipp/Ipp.kt          IPP 二进制编解码（RFC 8010）
app/src/main/java/.../ipp/IppServer.kt    HTTP/1.1 + 操作分发 + 属性回填
app/src/main/java/.../ipp/JobStore.kt     串行作业队列（落盘 + 状态机）
app/src/main/java/.../mdns/MdnsAdvertiser.kt  NsdManager 广播 _ipp._tcp
app/src/main/java/.../print/RawPrint.kt   PCL 直通 + 文档类型嗅探
app/src/main/java/.../PrintService.kt     前台服务宿主
tools/ipptest/ippclient.py                协议探针（标准库，无依赖）
```

---

## 2. 验收结果

| 项 | 判据 | 结果 |
|---|---|---|
| 端口监听 | `IppServer.start()` 后能被局域网访问 | **通过**：`IPP 已在 0.0.0.0:8631 监听` |
| HTTP 可达 | PC 上 `curl http://192.168.123.60:8631/` | **通过**：`HTTP 200`，1584 字节状态页 |
| Get-Printer-Attributes | 返回完整属性集，标签类型正确 | **通过**：38 个属性，`printer-state=3`、`document-format-supported=application/pdf`、`sides-supported`、`printer-resolution-supported=300,600` 均正确解析 |
| Validate-Job | `successful-ok`，且回显版本 1.1 | **通过** |
| Print-Job（PDF） | 文档经 PDF→条带渲染→编码→USB 出纸 | **通过**：作业 #1，89011 字节 PDF → 600dpi → 226873 字节 PCL，编码 1006ms、USB 180ms，`job-state=9 completed` |
| Print-Job（PCL 直通） | 已渲染的 PCL 不经重渲染直接送 USB | **通过**：作业 #1（重启服务后计数），8045 字节，USB 1ms |
| Get-Job-Attributes | 能查到状态与 `job-impressions-completed` | **通过**：`job-state=9`，`job-state-message` 带完整日志 |
| Get-Jobs | 列出历史作业 | **通过** |
| 状态页 | 浏览器可读，含队列 | **通过**：`#1 ipp-600 完成 11-16 11:44:45` |
| mDNS | `NsdManager` 注册成功 | **通过**：`mDNS 已注册：WPrint-LJ2600D _ipp._tcp 端口 8631` |
| 前台服务 | 熄屏不冻结 | 已实现（`connectedDevice` → `specialUse` 两级退路），待长时间验证 |

关键日志（PDF 路径）：

```
I WPrint/Ipp: IPP op=0x0002 id=42 Print-Job v1.1 body=89255 ct=application/ipp path=/ipp/print
I WPrint/Ipp: 接受作业 #1「ipp-600」89011 字节（PDF）
I WPrint/Jobs: #1 目标：A4 4960x7015 @600dpi duplex=false
I WPrint/Jobs: #1 整页 ARGB 需 132.7 MB → 改走条带渲染（每带 256 行）
I WPrint/Jobs: #1 编码 1006 ms，输出 226873 字节（5.2% of 1bpp 原始大小）
I WPrint/Jobs: #1 USB 写入 226873 字节，耗时 180 ms
```

关键日志（PCL 直通）：

```
I WPrint/Ipp: 接受作业 #1「pcl-check」8045 字节（PCL/PJL）
I WPrint/Raw: PCL 直通：0001-pcl-check.pcl（8045 字节）
I WPrint/Raw: USB 写入 8045 字节，耗时 1 ms
```

---

## 3. 为什么必须加 PCL 直通

这是实施中最重要的一次判断修正。

Windows 那台机器上**已经存在**一条指向本机的 IPP 队列：

```
Name       : \\http://192.168.123.60:631\CUPS_LJ2600D
DriverName : Lenovo LJ2600D          ← 厂商驱动，不是 Microsoft IPP Class Driver
PortName   : http://192.168.123.60:631/printers/CUPS_LJ2600D
```

厂商驱动渲染出来的是 **PCL5**，不是 PDF。原计划「只收 PDF」的服务器会把它全部拒掉
（`client-error-document-format-not-supported`），而且用户看不出原因。

所以加了第二条路径：**字节嗅探 + PCL 原样透传**。

| 首字节特征 | 判定 | 处理 |
|---|---|---|
| `%PDF` | PDF | 走 PdfRenderer → brlaser 管线 |
| `ESC%-12345X` / `ESC E` / 含 `@PJL` / 含 `ESC&l` | PCL | **原样写 USB**，不重渲染 |
| `RaS2` / `PwgR` | PWG Raster | 明确拒收并记日志（v2 才实现解码） |
| `%!PS` | PostScript | 明确拒收 |
| 其它 | 未知 | 拒收，日志打出头 16 字节十六进制 |

直通不是偷懒：这台机器的固件本来就懂 PCL，Windows 那边有完整字体与图形栈，
渲染质量只会比手机上的 PdfRenderer 更好。语义上我们就是一个透明的 IPP→USB 桥，
与老式 CUPS raw 队列完全一致。

**副作用**：走 PCL 直通时，手机界面上的「画质 / 单双面」不生效——那些由 Windows
的驱动设置决定。这符合预期（驱动才是渲染方），但要在界面上说清楚。

---

## 4. 已知限制与遗留

1. **端口 8631，不是 631。** `bind()` 到 1024 以下需要 `CAP_NET_BIND_SERVICE`，
   非 Root 应用拿不到。所以 Windows 里那条旧队列的端口 URL 必须从
   `http://192.168.123.60:631/printers/CUPS_LJ2600D`
   改成 `http://192.168.123.60:8631/ipp/print`（或新建一台）。
   服务器接受**任意** POST 路径，所以只改端口号、保留旧路径也能工作。
2. ~~PWG Raster 未实现~~ **→ 已实现，见第 7 节。** Windows 若用
   「Microsoft IPP Class Driver」而非厂商驱动，送的是 PWG Raster。
   现在 `image/pwg-raster` 已声明并支持解码，这类客户端可以直接装、直接打。
3. **服务与 Termux 打印服务不能共存。** 同一个 Bulk OUT 端点只能有一个写者。
   两者同时跑会产生交错字节。当前做法：进 `PrintService` 前先 `claimInterface`，
   抢不到就报 `device-error`。
4. **mDNS 只是锦上添花。** Windows 的「添加打印机」向导不看 mDNS，必须手填 URL。
   macOS/iOS 能看到 `WPrint-LJ2600D`，但因为不应答 URF，选中后会判定为不支持格式。
5. **作业状态不持久化。** 进程重启后 `job-id` 从 1 重新计数，历史记录清空。
   对打印场景够用（客户端不会跨重启查作业状态）。

---

## 5. 复现方法

```bash
# 1. 构建 + 安装
./build.sh && ./tools/adb.sh install -r -g app/build/outputs/apk/debug/app-debug.apk

# 2. 起服务（首次会弹 USB 授权，需点「确定」）
./tools/adb.sh shell am start -n com.cawzen.printserver/.MainActivity --es run server

# 3. 协议自检（不耗纸）
python tools/ipptest/ippclient.py attrs
python tools/ipptest/ippclient.py validate testdata/stage3/selftest.pdf

# 4. 真打一页
python tools/ipptest/ippclient.py print testdata/stage3/selftest.pdf --name ipp-600

# 5. PCL 直通路径
python tools/ipptest/ippclient.py print testdata/stage3/sample_300.pcl --name pcl-check

# 6. 查作业
python tools/ipptest/ippclient.py jobs
```

`tools/adb.sh` 每次调用都会重新做 mDNS 发现并连接——因为 adb server 会随 shell 退出，
而无线调试端口每次重启都变。不要硬编码 `IP:port`。

---

## 6. Windows 侧配置（需管理员）

```
设置 → 蓝牙和其他设备 → 打印机和扫描仪
  → 选中 \\http://192.168.123.60:631\CUPS_LJ2600D → 打印机属性 → 端口
  → 选中那条 http:// 端口 → 配置端口
  → URL 改为 http://192.168.123.60:8631/ipp/print → 确定
```

或者新建：

```
添加打印机 → 我需要的打印机不在列表中 → 按名称选择共享打印机
  → http://192.168.123.60:8631/ipp/print
  → 驱动选 Lenovo LJ2600D（已经装过，直接可选）
```

打印时若不出纸，按顺序看三处：手机 App 里的「队列 / 最近作业」→ 通知栏 → Logcat 的
`WPrint/Ipp`。三处分别对应「没收到」「收到了但失败」「收到了且成功」。

---

## 7. PWG Raster 支持（补齐通用性）

### 7.1 为什么必须有

第 3 节说厂商驱动送 PCL，我们直通就行。但那只覆盖「装了联想驱动」的电脑。
换一台没装驱动的 Windows 去添加这台打印机，系统会分配 **Microsoft IPP Class Driver**，
它不看 PDF、也不看 PCL，只发 **PWG Raster**。原先遇到这种情况会直接拒收，
表现是「打印机装上了但打什么都不出纸」。

### 7.2 格式核对（不靠猜）

字段偏移全部对着 CUPS 的 `cups/raster.h` 逐项数过，并用「总长必须等于 1796」自校验：

```
每页 = [4 字节 'RaS2'] + [1796 字节 cups_page_header2_t] + [height × bytesPerLine 像素]
```

关键偏移：`cupsWidth` 372、`cupsHeight` 376、`cupsBitsPerPixel` 388、
`cupsBytesPerLine` 392、`cupsColorSpace` 400、`cupsCompression` 404、
`cupsInteger[0]`(TotalPageCount) 452。

支持 `sGray 8bpp`（cs=18）、`sRGB 24bpp`（cs=19）、`1bpp`（bit=0 是黑，
与 PCL 的「1=上墨」相反，照搬会打出反相页）。CMYK / CIELab / ICC 明确拒绝 ——
猜错一个分量顺序就是整页反相，比报错难查得多。

### 7.3 验证方法：CRC32 对账

PWG 是 8bpp 无压缩，**解码出的灰度数组应当与生成器的画布逐字节相同**。
两端各算一个 CRC32，直接对账，不需要反解 PCL、也不依赖肉眼：

| 项目 | 生成器 | 手机解码 | 结论 |
|---|---|---|---|
| 单页 600dpi | `C4F524E1` | `C4F524E1` | 逐字节一致 |
| 两页·第 1 页 | `CAACFECA` | `CAACFECA` | 逐字节一致 |
| 两页·第 2 页 | `3673DCC3` | `3673DCC3` | 逐字节一致 |

样本生成器：`tools/ipptest/mkpwg.py`（纯标准库，按规范逐字段构造页头）。
图案里刻意放了外框（验行宽）、同心圆与对角线（验采样）、8 级灰阶条（验极性）、
逐页页号（验多页不串序）。

### 7.4 实测

| 场景 | 结果 |
|---|---|
| 单页 600dpi（34.8MB） | 解码 + 编码 199ms，输出 146696 字节，USB 107ms |
| 两页 600dpi（69.6MB） | 两页同作业，编码 322ms，输出 292970 字节，USB 184ms |
| 两页 + 双面 | `duplex=true`，292975 字节，USB 119ms |
| 深度 Doze（`force-idle`）下 | 前台服务照常收作业，220713 字节，USB 173ms |

### 7.5 顺带修掉的一个硬伤：请求体上限

原 `MAX_BODY = 64MB`，且**整个 body 读进内存**。两页 600dpi 的 PWG 是 69.6MB，
正好撞上：服务端直接断连，客户端只看到 `ConnectionResetError: 远程主机强迫关闭了
一个现有连接`，完全看不出是体积限制。真实的多页文档（十页就 350MB）根本无法工作。

改成流式：属性组在报文开头，先读 256KB 解析出 `dataOffset`，**其余字节边收边写文件**，
再由 `JobStore` 用 `rename` 搬进作业目录。上限放到 512MB，内存占用与文档大小无关。

配套改了 `Ipp.Request.complete`：区分「读到 end-of-attributes」和「缓冲区被读满」。
不区分的话，属性组超长时会把属性组尾巴当成文档开头，错误会指向 PdfRenderer 而不是
HTTP 层，极难定位。

### 7.6 一处重要的语义修正：dpi 跟文档走

PDF 里没有分辨率概念，界面选 600dpi 就光栅化成 600dpi，这是对的。
但 PWG Raster 是客户端**已经渲染好**的位图，页头里的 `HWResolution` 就是它真实采样率。

首版沿用界面选项，结果把 300dpi 的图放大到 600dpi 网格上，边缘全糊 ——
看起来「用了 600dpi」，实际清晰度还是 300，只是文件更大。

现在：文档分辨率属于 {300, 600} 就直接照用；否则归一到界面选项并缩放。
界面显示 `文档为 300dpi，界面选的是 600，以文档为准`，不静默。
