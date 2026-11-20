# AirPrint（URF + mDNS 子类型）验收记录

目标：iPhone / iPad / Mac 在打印面板里**直接看到**「WPrint-LJ2600D」，选中即可打印，
不需要填 IP、不需要装 App、不需要驱动。

结论：**协议链路已全通并逐字节验证**，等真机 iPhone 目视确认。

---

## 1. 为什么「加个 TXT 记录」不够

AirPrint 的发现条件不是「有 `_ipp._tcp` 就行」，四条同时成立才会出现在 iOS 列表里：

| # | 条件 | 说明 |
|---|---|---|
| 1 | PTR `_universal._sub._ipp._tcp.local` | **子类型**。iOS 只列带这个子类型的服务 |
| 2 | TXT 里 `URF=...` 且 `pdl` 含 `image/urf` | AirPrint 的模型是「客户端光栅化，打印机收 URF」 |
| 3 | TXT 的 `Color` / `Duplex` 与实际能力一致 | 否则 App 里给出的选项组合打不出来 |
| 4 | `rp` 与 IPP 路由一致、`adminurl` 可打开 | 否则选中后作业投递失败 |

其中第 1 条是硬门槛，而 **Android 的 `NsdManager` 没有任何子类型 API**
（`NsdServiceInfo` 只有 name/type/port/TXT，从 API 16 到 35 都没变）。

后果很具体：`NsdManager` 广播的 `_ipp._tcp` 在 macOS 的 `ippfind` 里看得见、
Windows 手动加 URL 也能用，但 iPhone 的打印面板里**永远不出现**。这不是 TXT 写错了，
是协议入口就不对——所以只能自己发 mDNS 报文。

## 2. 自实现的 mDNS 响应器

`mdns/MdnsResponder.kt`：只做「响应器」该做的事，不做缓存/去重/冲突探测
（那些是浏览器端的职责）。

| 项 | 取值 | 理由 |
|---|---|---|
| 组地址 | `224.0.0.251:5353` | RFC 6762 |
| 绑定 | `MulticastSocket` + `SO_REUSEADDR` | 必须从 **5353 端口发出**，否则标准客户端当伪造包丢掉；REUSEADDR 才能和系统 `mdnsd` 共存 |
| 出接口 | `NetInfo.wifi()` 给的接口名（`wlan0`） | 绑到 `tun0` 就等于宣告一个没人能连的地址 |
| PTR 服务 | `_ipp._tcp.local`，TTL 4500 | |
| PTR 子类型 | `_universal._sub._ipp._tcp.local`，TTL 4500 | **AirPrint 的入口** |
| PTR 枚举 | `_services._dns-sd._udp.local` | 「浏览可用服务类型」用 |
| SRV | `pri=0 weight=0 port=8631 → <host>.local`，TTL 120 | 端口就是 IPP 端口 |
| TXT | 20 个键，见 `mdns/AirPrintTxt.kt`，TTL 4500 | 与 IPP 的 `urf-supported` 同源 |
| A | `<host>.local → 局域网 IP`，TTL 120，cache-flush | 主机名由 `ANDROID_ID` 派生，稳定不撞 |

行为：

- 启动时公告 3 次（t=0/1/2 秒），之后每 5 分钟刷新；IP 变了立刻重新公告。
- 退出时发 TTL=0 的告别包，客户端立刻从列表里去掉。
- 收到查询：源端口是 5353 就回**组播**（延迟 20–120ms 随机，避免抢答），
  否则回**单播**（RFC 6762 §6.7）。
- 持有 `MulticastLock`：否则熄屏后 WiFi 固件会丢组播，表现是「亮屏能搜到、锁屏搜不到」。
- 5353 绑不上时（少数定制 ROM）自动降级回 `MdnsAdvertiser`（`NsdManager`）：
  局域网发现还在，AirPrint 不可用 —— 界面上会明说「降级：AirPrint 不可用」。

### 一个真实的坑：VPN 会偷走地址

开着 Tailscale/Clash 时，`ConnectivityManager` 给 VPN 网络的 capabilities 里
**同时带着底层传输类型（WIFI）**，于是「找 WiFi 网络的 IPv4」最先命中 `tun0`
和 `100.64/10` 的地址。实测第一次跑就中招：

```
IPP 已在 0.0.0.0:8631 监听，URL = ipp://100.111.57.20:8631/ipp/print
mDNS 已启动：… → 100.111.57.20:8631（接口 tun0）
```

修法（`net/NetInfo.kt`）：跳过带 `TRANSPORT_VPN` 或缺 `NET_CAPABILITY_NOT_VPN`
的网络，并且在候选地址里优先 `isSiteLocalAddress`。

## 3. URF（Apple Raster）解码

`print/Urf.kt`。格式与 PWG Raster **完全不同**，不能复用 PWG 的解码器
（只能复用解码之后的编码/USB 骨架，见 `print/RasterPage.kt` 的 `RasterPrint`）：

```
文件头 12 B:  "UNIRAST\0" | u32be 页数
页头   32 B:  bpp(0) colorspace(1) duplex(2) quality(3) mediaType(4) inputSlot(5)
              outputBin(6) copies(7) finishings(8..11) width(12..15) height(16..19)
              dpi(20..23) 保留(24..31)
像素数据:     逐行 —— 1 字节「本行重复次数-1」，然后是 PackBits 游程直到凑满行宽
                c <  0x80 → 下一像素重复 c+1 次
                c == 0x80 → 本行提前结束，余白
                c >  0x80 → 接着 257-c 个像素原样照抄
```

两个**照抄就会出错**的地方：

1. **游程语义与标准 PackBits 正好反向**。标准里 `0x00–0x7F` 是字面量，
   URF 里同一个区间是「重复」。按标准实现会得到一页乱码。
2. **`0x80` 不是「保留/错误」，是「本行到此为止，剩下全白」**。
   把它当错误抛掉，每页都会在第一个空白段失败。

`0x80` 的语义不是猜的：cups-filters 的 `urftopdf`、Ghostscript 的 URF 解释器、
go-unirast 三个独立实现一致；`printa-ble-core` 则用「暴力试遍候选语义，只保留能
正好消费到文件末尾那一种」的方式确认过。

内存：A4@600 单页灰度 34.8MB，一页一解、解完即编码，峰值与页数无关。
单页像素数上限 50M（A4@1200 是 139M）—— 超了明确报错，不 OOM。

## 4. IPP 侧补齐

| 项 | 内容 |
|---|---|
| `document-format-supported` | 追加 `image/urf` |
| `urf-supported` | `W8 SRGB24 CP1 IS1 MT1-3-8 OB10 PQ3-4-5 RS300-600 DM1 V1.4`（与 TXT 的 `URF=` 同源） |
| `printer-uuid` | `urn:uuid:` + `ANDROID_ID` 派生。iOS 靠它记住纸张/单双面偏好，每次启动换一个就会丢设置 |
| `orientation-requested-supported` | 3/4/5/6。不声明的话 iOS 面板没有方向选项 |
| `operations-supported` | 追加 `Create-Job` / `Send-Document` / `Close-Job` |

**Create-Job / Send-Document 是必须的**：iOS 经常不用 `Print-Job` 一口气发完，
而是先建作业号、再单独送文档。缺这一段的表现是「选中打印机、点打印没反应」，
服务端日志只有一个 `0x0501 operation-not-supported`，看不出是缺哪一步。

`JobStore` 为此加了「作业已建、文档未到」的中间态（`documentReady=false`）：
这期间不进队列、不算 `queued-job-count`，避免打印机状态一直显示忙。

## 5. 验收（可复现）

### 5.1 解码正确性：CRC32 逐字节对账

样本用 `tools/ipptest/mkurf.py` 生成（图案与 PWG 样本同源，便于横向对比）。
生成器把画布的 CRC32 打出来，手机端在编码前对**最终灰度缓冲**算 CRC32，两边必须相等：

| 样本 | 参数 | 生成器 CRC32 | 手机端 CRC32 | 结论 |
|---|---|---|---|---|
| `urf300-1p.urf` | 1 页 2480×3508 8bpp | `FC1D398D` | `FC1D398D` | 一致 |
| `urf300-2p.urf` | 2 页 8bpp 双面 | `FC1D398D` / `B76FDAA1` | `FC1D398D` / `B76FDAA1` | 一致 |
| `urf300-rgb.urf` | 1 页 24bpp sRGB | `FC1D398D` | `FC1D398D` | 一致（RGB→灰度的 Rec.601 转换正确） |
| `pwg300.pwg`（回归） | 1 页 8bpp sGray | `5AC93A3D` | `5AC93A3D` | 一致（`RasterPrint` 抽取没有破坏 PWG 路径） |

解码速度：300dpi 单页 24bpp 约 195ms（含 RGB→灰度），8bpp 约 240ms（含抖动前的编码准备）。
输入 318818 字节 → PCL 95098 字节 → USB 73ms。

### 5.2 mDNS：从 PC 上真的问一次

```
$ python tools/ipptest/mdnsquery.py --subtype --iface 192.168.123.66 --from-port 5353
查询 _universal._sub._ipp._tcp.local. PTR  →  224.0.0.251:5353

来自 192.168.123.60:5353   报文 515 字节   flags=0x8400  an=1 ar=3
  [answer]     PTR  _universal._sub._ipp._tcp.local  ttl=4500  →  WPrint-LJ2600D._ipp._tcp.local
  [additional] SRV  WPrint-LJ2600D._ipp._tcp.local  ttl=120 cache-flush  →  pri=0 weight=0 port=8631 target=wprint-b4f0.local
  [additional] TXT  WPrint-LJ2600D._ipp._tcp.local  ttl=4500 cache-flush  →  txtvers=1 | qtotal=1 | rp=ipp/print | ty=Lenovo LJ2600D | … | pdl=application/pdf,image/urf | URF=W8,SRGB24,CP1,IS1,MT1-3-8,OB10,PQ3-4-5,RS300-600,DM1,V1.4 | Color=F | Duplex=T | …
  [additional] A    wprint-b4f0.local  ttl=120 cache-flush  →  192.168.123.60
```

公告（`--listen`）里同样能看到两条 PTR、SRV、TXT、A，压缩指针解析正常。

> 注意：Windows 把 WiFi 判成「公用网络」时，防火墙会挡掉入站的**单播**应答，
> 查询-应答看起来完全不通。用 `--from-port 5353`（像 iOS 那样从 5353 发）
> 让对端回组播，就能绕开；或者用 `--listen` 直接看公告。

### 5.3 IPP 全流程（模拟 iOS）

```
$ python tools/ipptest/ippclient.py --host 192.168.123.60 airprint testdata/stage4/urf300-2p.urf --duplex
文档：… 318818 字节  document-format=image/urf
[1/3] Create-Job      → job-id=2, job-state=3 (pending), message="held for document"
[2/3] Send-Document   → job-state=5 (processing)
[3/3] Close-Job       → successful-ok
$ ippclient.py job 2  →  job-state=9 (completed), job-impressions=2
```

服务端日志（节选）：

```
[URF] UNIRAST 文件头：声明 2 页
[URF]   第 1 页：2480x3508 @300dpi 8bpp cs=0 quality=4 duplex标志=1（共 2 页）
[URF]   第 1 页灰度 CRC32=FC1D398D
[URF]   第 2 页灰度 CRC32=B76FDAA1
[URF] 编码 419 ms，输出 95098 字节
[URF] USB 写入 95098 字节，耗时 73 ms
```

## 6. 已知缺口

| 缺口 | 影响 | 备注 |
|---|---|---|
| 没有 AAAA 记录 | iOS 问 IPv6 地址时无应答，会回落到 A 记录 | 局域网 IPv4 足够；要支持纯 IPv6 网络需补 AAAA |
| 没做名字冲突探测 | 若局域网里已有同名 `WPrint-LJ2600D`，两个服务会打架 | 单机使用不会遇到；要彻底解决需实现 RFC 6762 §8.1 探测 |
| 没声明 `media-size-supported` 集合 | 部分 iOS 版本的纸张下拉可能只有默认项 | 若真机发现纸张选项缺失，再补这一组 collection |
| URF 的双面页序未在真机验证 | iOS 可能按「背面在前」成对发送，我们按收到顺序送 brlaser | 已按 `sides` 走双面；若真机背面内容错位，改法是在 `Urf` 里把页对反转 |
| 不支持 TLS（`_ipps._tcp`） | 不广播也不需要 | AirPrint 允许明文 IPP |
| 与 Termux 打印服务不可共存 | 同一个 Bulk OUT 只能一个写者 | 已有 `UsbGate` 串行，但跨进程无解 |
