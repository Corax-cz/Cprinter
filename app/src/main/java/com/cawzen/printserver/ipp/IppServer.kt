package com.cawzen.printserver.ipp

import android.content.Context
import android.util.Log
import com.cawzen.printserver.AppSettings
import com.cawzen.printserver.PrintOptions
import com.cawzen.printserver.usb.UsbPrinter
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * IPP 服务器（Stage 3）。手写 HTTP/1.1 + IPP，不用 Ktor/Netty。
 *
 * 为什么不用 Web 框架：这里只需要「读一个 POST 的 body，回一个 POST 的 body」。
 * 引一个容器会带来线程模型、字符集、keep-alive、最大体积这些额外的待调参数，
 * 而打印机客户端对 HTTP 的要求极低（单请求、短连接、Content-Type 固定）。
 *
 * 兼容性要点（都是实测容易翻车的地方）：
 * - 必须支持 `Expect: 100-continue`。Windows 与 CUPS 发大 body 前会等这个中间响应，
 *   不回就一直卡在「正在发送」。
 * - 必须支持 `Transfer-Encoding: chunked`。CUPS 默认用它发文档。
 * - `Content-Type` 不校验，只记日志。有客户端发 `application/ipp` 以外的值。
 * - 响应必须带 `Content-Length`（不能靠关连接表示结束），否则部分客户端读不到完整报文。
 */
class IppServer(
    private val context: Context,
    private val jobs: JobStore,
    private val optionsProvider: () -> PrintOptions,
    private val onLog: (String) -> Unit = {},
) {

    companion object {
        /** 不用 631：低于 1024 的端口在非 Root 应用里绑不上。 */
        const val DEFAULT_PORT = 8631
        const val RP = "ipp/print"

        /**
         * 请求体上限。
         *
         * 原先是 64MB，那是按「一页 PDF」估的，实际不够：Windows 送 PWG Raster 时，
         * 一页 600dpi 就是 35MB，十页文档 350MB。撞上这个上限的表现还特别差——
         * 服务端直接断开连接，客户端只看到「远程主机强迫关闭了一个现有连接」，
         * 完全看不出是体积限制。
         *
         * 现在文档不再攒在内存里，而是边收边写文件，所以上限只受磁盘约束。
         */
        private const val MAX_BODY = 512L * 1024 * 1024

        /**
         * 一次读入多少字节用来解析属性组。
         *
         * IPP 的属性组一定在报文最前面，正常只有几百字节；留 256KB 是为了容纳
         * 塞满 job 属性的客户端。超过这个量还没读到 end-of-attributes 就报错，
         * 不会拿错误的偏移去切文档。
         */
        private const val HEAD_MAX = 256 * 1024

        /**
         * `printer-icons` 提供的三种尺寸（PWG 5100.13 推荐 48/128/512 各一张）。
         *
         * 三个尺寸都要在：客户端按显示场景挑，只给一个时部分实现会当作
         * 「图标资源不完整」直接不用。
         */
        val ICON_SIZES = listOf(48, 128, 512)

        private const val TAG = "WPrint/Ipp"
    }

    @Volatile
    /**
     * 所有监听 socket。IPv4 与 IPv6 可能落在同一个双栈 socket 上，也可能各占一个，
     * 取决于内核的 `net.ipv6.bindv6only`，所以用列表统一管理。
     */
    private var sockets: MutableList<ServerSocket> = mutableListOf()

    @Volatile
    var port: Int = DEFAULT_PORT
        private set

    @Volatile
    var running: Boolean = false
        private set

    private var acceptThread: Thread? = null
    private val pool = Executors.newFixedThreadPool(4)
    private val startedAt = System.currentTimeMillis()
    /** 只在第一个 Get-Printer-Attributes 上把客户端要的属性名记一次，避免刷屏。 */
    private val attrDumpCount = java.util.concurrent.atomic.AtomicInteger(0)

    private fun log(msg: String) {
        Log.i(TAG, msg)
        onLog(msg)
    }

    // ── 生命周期 ────────────────────────────────────────────────────────────

    /**
     * 绑定端口并开始接受连接。
     *
     * 端口被占（上一轮没退干净，或者另一个实例在跑）时向上找 16 个端口。
     * 不做静默重试以外的处理：绑不上就应该让上层看到失败。
     */
    fun start(preferredPort: Int = DEFAULT_PORT): Boolean {
        if (running) return true
        var bound: List<ServerSocket> = emptyList()
        var p = preferredPort
        while (p < preferredPort + 16) {
            bound = bindAll(p)
            if (bound.isNotEmpty()) {
                port = p
                break
            }
            Log.w(TAG, "端口 $p 绑定失败")
            p++
        }
        if (bound.isEmpty()) {
            log("无法绑定任何端口（从 $preferredPort 起试了 16 个）")
            return false
        }
        sockets = bound.toMutableList()
        running = true

        bound.forEach { s ->
            thread(name = "ipp-accept-${s.inetAddress.hostAddress}", isDaemon = true) {
                acceptLoop(s)
            }
        }
        val desc = bound.joinToString(" + ") {
            if (it.inetAddress is Inet6Address) "IPv6[::]" else "IPv4[0.0.0.0]"
        }
        log("IPP 已在 $desc:$port 监听，URL = ${printerUri()}")
        return true
    }

    /**
     * 在本端口上尽量同时占住 IPv4 与 IPv6，返回实际的 socket 集合。
     *
     * 之前的实现只 `ServerSocket(port)`，在 Java 里等价于绑 `0.0.0.0` —— **纯 IPv4**。
     * 而 iPhone 从 mDNS 看到的还有我们的 IPv6 link-local（响应报文的源地址），
     * 一旦它优先用 IPv6 连，SYN 没人接，失败在 iOS 那头是完全静默的：打印机还在
     * 列表里，点打印什么都不发生。
     *
     * 顺序是「先 :: 再 0.0.0.0」，让内核决定两者能不能共存：
     *
     * - `bindv6only=0`（Linux 常见）：`::` 本身就是双栈，再绑 `0.0.0.0` 会
     *   `EADDRINUSE`，忽略即可，那个 `::` 已经能收 IPv4。
     * - `bindv6only=1`（**实测 Android 就是 1**）：`::` 只收 IPv6，`0.0.0.0`
     *   能独立绑上，两个都在。
     *
     * 刻意**不用**「绑完再连一次 127.0.0.1 探活」的办法：那个探活会被 accept
     * 队列吞掉、也会受 backlog 时机影响，实测在 Android 上直接误判成
     * 「IPv4 不可达」，结果两个 socket 都没留下。
     */
    private fun bindAll(p: Int): List<ServerSocket> {
        val out = ArrayList<ServerSocket>(2)
        runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("::"), p))
            }
        }.getOrNull()?.let { out += it }

        runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(p))
            }
        }.getOrNull()?.let { out += it }

        return out
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val client = try {
                s.accept()
            } catch (e: SocketException) {
                break                          // stop() 关掉了 socket
            } catch (t: Throwable) {
                Log.w(TAG, "accept 异常：${t.message}")
                continue
            }
            try {
                pool.execute { handleClient(client) }
            } catch (t: Throwable) {
                runCatching { client.close() }
            }
        }
    }

    fun stop() {
        running = false
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        pool.shutdownNow()
        acceptThread = null
        log("IPP 已停止")
    }

    // ── 对外信息 ────────────────────────────────────────────────────────────

    /**
     * 对外地址。以系统给的 WiFi 网段为准（见 [com.cawzen.printserver.net.NetInfo]），
     * 不是「遍历接口取第一个站点内网地址」—— 手机同时有蜂窝网时那个顺序不受控，
     * 挑错了就会在 `printer-uri-supported` 里给出一个别人连不上的地址。
     */
    fun localIp(): String = com.cawzen.printserver.net.NetInfo.ip(context)

    fun printerUri(): String = "ipp://${localIp()}:$port/$RP"

    fun httpUri(): String = "http://${localIp()}:$port/"

    // ── HTTP 层 ────────────────────────────────────────────────────────────

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 30_000
                val input = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()

                val requestLine = readLine(input)
                if (requestLine.isNullOrBlank()) {
                    // 客户端建了连接却不发请求（端口探测、或者握手中途放弃）。
                    // 以前这里直接 return，事后完全看不出 iOS 到底来没来过。
                    log("空请求：${socket.remoteSocketAddress} 已连接但不发数据，关闭")
                    return
                }
                val parts = requestLine.split(' ')
                if (parts.size < 2) {
                    log("畸形请求行：${socket.remoteSocketAddress} → $requestLine")
                    return
                }
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')

                var contentLength = 0L
                var chunked = false
                var expectContinue = false
                var contentType = ""
                var userAgent = ""
                var hostHeader = ""
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx <= 0) continue
                    val key = line.substring(0, idx).trim().lowercase()
                    val value = line.substring(idx + 1).trim()
                    when (key) {
                        "content-length" -> contentLength = value.toLongOrNull() ?: 0L
                        "transfer-encoding" ->
                            if (value.contains("chunked", ignoreCase = true)) chunked = true
                        "expect" ->
                            if (value.contains("100-continue", ignoreCase = true)) expectContinue = true
                        "content-type" -> contentType = value
                        "user-agent" -> userAgent = value
                        "host" -> hostHeader = value
                    }
                }
                // 每个请求都记一行，含对端、UA、Host。iPhone 的 UA 里带 `iOS`，
                // 出问题时第一眼就能区分「手机来了」还是「手机根本没来」。
                log(
                    "HTTP $method $path from ${socket.remoteSocketAddress}" +
                        " host=${hostHeader.ifBlank { "-" }}" +
                        " len=$contentLength${if (chunked) " chunked" else ""}" +
                        " ua=${userAgent.ifBlank { "-" }}"
                )

                // 谁在用什么设备：UA 与 IP 在这里记一次，作业归因走 ThreadLocal。
                val remoteIp = socket.inetAddress?.hostAddress ?: ""
                ClientRegistry.setThreadClient(remoteIp, userAgent)
                ClientRegistry.touch(remoteIp, userAgent)

                // 设备白名单。只拦 POST —— 允许客户端读属性却拒绝提交，看起来
                // 像「打印机在但打不出来」，比直接 403 更难查。GET 是状态页，
                // 留着方便用户自己打开看日志。
                if (method == "POST" && !loopback(remoteIp)) {
                    val cfg = AppSettings.load(context)
                    if (!cfg.allowNewDevices && remoteIp !in cfg.approvedClients) {
                        ClientRegistry.recordBlocked(remoteIp, userAgent)
                        log("拒绝未授权设备 $remoteIp（设置里关闭了「允许新设备」）")
                        respondText(
                            out, "403 Forbidden",
                            "此打印机启用了设备白名单，$remoteIp 尚未授权。\n" +
                                "请在手机 App「共享 → 已连接设备」里允许这台设备。",
                        )
                        return
                    }
                }

                if (contentLength > MAX_BODY) {
                    respondText(out, "413 Payload Too Large", "请求体过大")
                    return
                }
                if (expectContinue) {
                    // 客户端在等这个「可以发了」，不回就一直挂住。
                    out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                    out.flush()
                }

                when {
                    // 任何 POST 都当 IPP 处理，不校验路径。原因是 Windows 里
                    // 已有的 IPP 端口 URL 形如 /printers/某个队列名 —— 用户在
                    // 端口设置里只改端口号（631→8631）时路径还是旧的那个，
                    // 严格匹配路径会把这种情况全部拒掉，而这里没有任何
                    // 安全理由需要校验路径（服务只监听局域网、只接受 IPP）。
                    method == "POST" -> {
                        // 流式：属性组解析完就把文档直接写到临时文件，不占内存。
                        val parsed = try {
                            splitIpp(input, contentLength, chunked)
                        } catch (t: Throwable) {
                            Log.w(TAG, "读取请求体失败：${t.message}")
                            respondText(out, "400 Bad Request", "请求体读取失败：${t.message}")
                            return
                        }
                        val req = parsed.first
                        val docFile = parsed.second
                        if (req == null) {
                            respond(
                                out, "200 OK", "application/ipp",
                                errorResponse(Ipp.STATUS_BAD_REQUEST, 1),
                            )
                            return
                        }
                        val payload = try {
                            handleIpp(req, docFile, contentType, path, hostHeader)
                        } catch (t: Throwable) {
                            Log.e(TAG, "IPP 处理异常", t)
                            errorResponse(Ipp.STATUS_INTERNAL_ERROR, 1)
                        } finally {
                            // 正常路径下 JobStore 已经把它改名搬走了，这里删的是
                            // 出错或空文档时的残留。
                            docFile?.delete()
                        }
                        respond(out, "200 OK", "application/ipp", payload)
                    }
                    method == "GET" && (path == "/" || path == "/index.html") -> {
                        respondText(out, "200 OK", statusHtml(), "text/html; charset=utf-8")
                    }
                    // 原始 USB 端口状态。见 usbDiagText() 的注释：用来定标位定义。
                    method == "GET" && path == "/usb" -> {
                        respondText(out, "200 OK", usbDiagText(), "text/plain; charset=utf-8")
                    }
                    // 手动给打印机发 UEL（`ESC%-12345X`）复位。
                    // 浏览器打开这个地址即可触发 —— 用 GET 是为了能用地址栏点，
                    // 这是个纯本地单用户工具，不做 CSRF 那套。
                    method == "GET" && path == "/uel" -> {
                        respondText(out, "200 OK", sendUelAndReport(), "text/plain; charset=utf-8")
                    }
                    // IPP Everywhere 要求 `printer-icons` 指向真实可取的图像，且三种
                    // 尺寸都要有（48/128/512）。取不到时客户端会把打印机降级成
                    // 「无图标设备」，部分版本干脆不给打印入口 —— 所以这几个路由
                    // 必须真的返回 PNG，不能只声明 URI。
                    method == "GET" && path.startsWith("/icon-") && path.endsWith(".png") -> {
                        val size = path.removePrefix("/icon-").removeSuffix(".png").toIntOrNull()
                        if (size == null || size !in ICON_SIZES) {
                            respondText(out, "404 Not Found", "可用尺寸：${ICON_SIZES.joinToString(",")}")
                        } else {
                            respond(out, "200 OK", "image/png", iconPng(size))
                        }
                    }
                    // `printer-strings-uri` 指向的本地化字符串表（PWG 5100.13 §10）。
                    method == "GET" && path == "/strings.json" -> {
                        respondText(out, "200 OK", stringsJson(), "application/json; charset=utf-8")
                    }
                    method == "HEAD" -> respond(out, "200 OK", "text/plain", ByteArray(0))
                    else -> respondText(out, "404 Not Found", "404  ${path}\n可用：GET / 、POST /$RP")
                }
            } catch (e: IOException) {
                Log.w(TAG, "HTTP 读写失败：${e.message}")
            } catch (t: Throwable) {
                Log.e(TAG, "连接处理异常", t)
            } finally {
                // 线程池会复用线程，不清掉会让下一个请求的作业挂到上一个 IP 上。
                ClientRegistry.clearThreadClient()
            }
        }
    }

    /** 本机回环不参与白名单：状态页、诊断和测试页都从 127.0.0.1 进来。 */
    private fun loopback(ip: String): Boolean =
        ip.startsWith("127.") || ip == "::1" || ip == "0:0:0:0:0:0:0:1" || ip.isBlank()

    private fun isIppPath(path: String): Boolean {
        val p = path.trimEnd('/').lowercase()
        return p == "/ipp/print" || p == "/ipp" || p == "/printers" || p == "/ipp/printer" || p == ""
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder(64)
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return sb.toString()      // 防御超长头
            sb.append(b.toChar())
        }
    }

    /**
     * 把 IPP 报文拆成「属性组」和「文档文件」。
     *
     * 属性组一定在最前面且很小，所以先读一段解析它；拿到 dataOffset 之后，
     * 剩下的字节直接流式写文件 —— 文档多大都不占内存。
     *
     * 返回的 File 由调用方负责清理（JobStore 会改名搬走，失败路径上删掉）。
     */
    private fun splitIpp(
        input: InputStream,
        contentLength: Long,
        chunked: Boolean,
    ): Pair<Ipp.Request?, File?> {
        if (chunked) {
            // iOS 的 Send-Document 常用 chunked（它不一定事先知道光栅化后的大小）。
            // 这里必须流式，见 splitIppChunked 的注释。
            return splitIppChunked(input)
        }

        if (contentLength <= 0) return null to null
        if (contentLength > MAX_BODY) {
            throw IOException("请求体 $contentLength 字节，超过上限 $MAX_BODY")
        }

        val headLen = minOf(contentLength, HEAD_MAX.toLong()).toInt()
        val head = readN(input, headLen.toLong())
        val req = Ipp.parse(head) ?: return null to null
        if (!req.complete) {
            // 宁可拒绝，也不能拿「缓冲区长度」当文档起点：那样会把属性组的尾巴
            // 当成文档交给 PdfRenderer，报错会指向完全无关的地方。
            throw IOException("属性组超过 $HEAD_MAX 字节仍未见结束标记")
        }

        val docTotal = contentLength - req.dataOffset
        if (docTotal <= 0) return req to null

        val remainInHead = head.size - req.dataOffset
        val f = newUploadFile()
        try {
            FileOutputStream(f).use { fos ->
                if (remainInHead > 0) fos.write(head, req.dataOffset, remainInHead)
                var copied = remainInHead.toLong()
                val buf = ByteArray(64 * 1024)
                while (copied < docTotal) {
                    val want = minOf(buf.size.toLong(), docTotal - copied).toInt()
                    val n = input.read(buf, 0, want)
                    if (n < 0) throw IOException("文档提前结束：已收 $copied / $docTotal")
                    fos.write(buf, 0, n)
                    copied += n
                }
            }
        } catch (t: Throwable) {
            f.delete()
            throw t
        }
        return req to f
    }

    /** 上传暂存。和 jobsDir 同在 /data/data 下，后面可以 rename 而不是拷贝。 */
    private fun newUploadFile(): File =
        File(context.cacheDir, "up-${System.nanoTime()}.bin")

    /** 读至多 [max] 字节；用于只嗅探文件开头，不把整个文档读进来。 */
    private fun readUpTo(input: InputStream, max: Int): ByteArray {
        val buf = ByteArray(max)
        var off = 0
        while (off < max) {
            val n = input.read(buf, off, max - off)
            if (n < 0) break
            off += n
        }
        return if (off == max) buf else buf.copyOf(off)
    }

    private fun readN(input: InputStream, n: Long): ByteArray {
        if (n <= 0) return ByteArray(0)
        if (n > MAX_BODY) throw IOException("body 过大：$n")
        val buf = ByteArray(n.toInt())
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) throw IOException("body 提前结束：$off/${buf.size}")
            off += r
        }
        return buf
    }

    /** chunked 解码：`<十六进制长度>\r\n<数据>\r\n`，长度为 0 结束。 */
    /**
     * 流式解 chunked 请求体。
     *
     * 旧实现把整个 body 收进 `ByteArrayOutputStream`，注释里的假设是「真正的文档
     * 上传都走 Content-Length」—— 这个假设对 iOS 不成立：它的 Send-Document 会用
     * `Transfer-Encoding: chunked`。600dpi 单页 URF 就是 35MB，先全量解到内存再
     * `toByteArray()` 复制一份，光这一下就是 70MB，叠上解码缓冲必然 OOM，而客户端
     * 那头只会看到连接被重置。
     *
     * 现在只把**属性组**留在内存（上限 [HEAD_MAX]），文档字节边解边落盘。判断
     * 属性组结束靠 [Ipp.parse] 的 `complete` 标志 —— 它只有在读到 0x03
     * (end-of-attributes-tag) 时才为真。
     */
    private fun splitIppChunked(input: InputStream): Pair<Ipp.Request?, File?> {
        val head = ByteArrayOutputStream(4096)
        var req: Ipp.Request? = null
        var doc: FileOutputStream? = null
        var docFile: File? = null
        var total = 0L

        fun openDoc(): FileOutputStream {
            var d = doc
            if (d == null) {
                val f = newUploadFile()
                docFile = f
                d = FileOutputStream(f)
                doc = d
            }
            return d
        }

        val buf = ByteArray(64 * 1024)
        try {
            while (true) {
                val sizeLine = readLine(input) ?: break
                val hex = sizeLine.substringBefore(';').trim()
                val size = hex.toIntOrNull(16)
                    ?: throw IOException("chunk 长度非法：$sizeLine")
                if (size == 0) {
                    readLine(input)      // trailer 之后的空行
                    break
                }
                var left = size
                while (left > 0) {
                    // 分次读，单个 chunk 再大也不会在这里分配整块缓冲区。
                    val n = input.read(buf, 0, minOf(buf.size, left))
                    if (n < 0) throw IOException("chunked 数据提前结束")
                    left -= n
                    total += n
                    if (total > MAX_BODY) throw IOException("chunked body 超过 $MAX_BODY")

                    if (req == null) {
                        head.write(buf, 0, n)
                        val bytes = head.toByteArray()
                        val parsed = Ipp.parse(bytes) ?: throw IOException("属性组无法解析")
                        if (parsed.complete) {
                            req = parsed
                            if (parsed.dataOffset < bytes.size) {
                                // 属性组和数据在同一个 chunk 里：剩下的直接进文件。
                                openDoc().write(bytes, parsed.dataOffset, bytes.size - parsed.dataOffset)
                            }
                            head.reset()
                        } else if (head.size() > HEAD_MAX) {
                            throw IOException("属性组超过 $HEAD_MAX 字节仍未见结束标记")
                        }
                    } else {
                        openDoc().write(buf, 0, n)
                    }
                }
                readLine(input)          // 每个 chunk 后的 CRLF
            }
        } catch (t: Throwable) {
            runCatching { doc?.close() }
            docFile?.delete()
            throw t
        }
        runCatching { doc?.close() }
        return req to docFile
    }

    private fun respond(out: OutputStream, status: String, contentType: String, body: ByteArray) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n")
            append("Server: wprint/1.0\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun respondText(out: OutputStream, status: String, text: String, type: String = "text/plain; charset=utf-8") {
        respond(out, status, type, text.toByteArray())
    }

    // ── IPP 层 ──────────────────────────────────────────────────────────────

    private fun handleIpp(
        req: Ipp.Request,
        docFile: File?,
        contentType: String,
        path: String,
        host: String = "",
    ): ByteArray {
        if (req.versionMajor != 1 && req.versionMajor != 2) {
            return errorResponse(Ipp.STATUS_VERSION_NOT_SUPPORTED, req.requestId)
        }
        log(
            "IPP op=0x%04X id=%d %s v%d.%d doc=%s ct=%s path=%s".format(
                req.operationId, req.requestId, operationName(req.operationId),
                (req.version shr 8) and 0xFF, req.version and 0xFF,
                docFile?.let { "${it.length()}B" } ?: "0B",
                contentType.ifBlank { "-" }, path,
            )
        )
        var verbose = false
        if (req.operationId == Ipp.OP_GET_PRINTER_ATTRIBUTES) {
            // 客户端只会要它关心的属性。把这份清单记下来，就知道它到底在找什么 ——
            // 「它点了哪些菜」比「我们端上了什么」更有诊断价值。
            //
            // 记的是 `requested-attributes` 的**值**，不是请求里出现的属性名：
            // 后者永远只有 charset/language/printer-uri/document-format 那几项，
            // 等于什么也没说。
            //
            // 现在**每次都记**：iOS 打开一次打印面板会连发几十个查询，
            // 「按下打印前最后那一个」才是它在最后确认什么 —— 只记前 6 条
            // 等于把最有用的信息丢掉了。诊断期不要怕刷屏。
            val n = attrDumpCount.incrementAndGet()
            verbose = true
            if (verbose) {
                val want = req.requestedAttributes()
                log(
                    "第 $n 次 Get-Printer-Attributes：requested-attributes(" +
                        "${want.size}) = ${if (want.isEmpty()) "「空，表示全都要」" else want.joinToString(",")}" +
                        " | document-format=${req.operationString("document-format") ?: "-"}" +
                        " | host=${host.ifBlank { "-" }}"
                )
                // 把 operation 组里**每一条**属性都打出来。
                //
                // 只看 requested-attributes 不够：iOS 反复要同一组属性却不提交作业时，
                // 判断依据可能在别处 —— 比如它有没有带 job-name、requesting-user-name，
                // 或者多带了某个我们不认识的属性。这些信息不打出来就只能靠猜，
                // 而这一轮已经因为「靠猜」浪费了太多时间。
                if (n <= 80) {
                    req.groups.firstOrNull { it.tag == Ipp.GROUP_OPERATION }?.attrs?.forEach { (k, v) ->
                        log("    · $k = ${v.joinToString(", ") { it.asString() ?: "(非字符串)" }}")
                    }
                }
            }
        }
        val payload = when (req.operationId) {
            Ipp.OP_GET_PRINTER_ATTRIBUTES,
            Ipp.OP_CUPS_GET_DEFAULT,
            Ipp.OP_CUPS_GET_PRINTERS,
            -> printerAttributes(req, host)

            Ipp.OP_VALIDATE_JOB -> validateJob(req)
            Ipp.OP_PRINT_JOB -> printJob(req, docFile)
            // AirPrint 客户端（尤其 iOS）经常不用 Print-Job 一口气发完，而是
            // Create-Job 拿到 job-id、再 Send-Document 送数据。不支持这一段的话
            // 表现是「打印机选中了、点打印没反应」，日志里只有一个 0x0501。
            Ipp.OP_CREATE_JOB -> createJob(req)
            Ipp.OP_SEND_DOCUMENT -> sendDocument(req, docFile)
            Ipp.OP_CLOSE_JOB -> closeJob(req)
            Ipp.OP_GET_JOB_ATTRIBUTES -> jobAttributes(req)
            Ipp.OP_GET_JOBS -> getJobs(req)
            Ipp.OP_CANCEL_JOB -> cancelJob(req)
            else -> statusOnly(req, Ipp.STATUS_OPERATION_NOT_SUPPORTED)
        }
        // 响应体大小也记一笔：如果它明显偏小（比如只有几十字节），说明走的是
        // 错误分支；如果正常（1.5KB 上下）却仍被拒，那问题就在内容而不是传输。
        if (verbose) log("  → 响应 ${payload.size} 字节")
        return payload
    }

    private fun operationName(op: Int): String = when (op) {
        Ipp.OP_PRINT_JOB -> "Print-Job"
        Ipp.OP_VALIDATE_JOB -> "Validate-Job"
        Ipp.OP_CREATE_JOB -> "Create-Job"
        Ipp.OP_SEND_DOCUMENT -> "Send-Document"
        Ipp.OP_CLOSE_JOB -> "Close-Job"
        Ipp.OP_CANCEL_JOB -> "Cancel-Job"
        Ipp.OP_GET_JOB_ATTRIBUTES -> "Get-Job-Attributes"
        Ipp.OP_GET_JOBS -> "Get-Jobs"
        Ipp.OP_GET_PRINTER_ATTRIBUTES -> "Get-Printer-Attributes"
        Ipp.OP_CUPS_GET_DEFAULT -> "CUPS-Get-Default"
        Ipp.OP_CUPS_GET_PRINTERS -> "CUPS-Get-Printers"
        else -> "op-$op"
    }

    private fun baseWriter(
        req: Ipp.Request,
        status: Int = Ipp.STATUS_OK,
        wanted: Set<String>? = null,
    ): Ipp.Writer =
        Ipp.Writer(
            status = status,
            requestId = req.requestId,
            // 回客户端发的版本号。客户端对「服务端擅自升版本」是敏感的，
            // 虽然 2.0 向下兼容，但回 1.1 给 1.1 的客户端最稳。
            version = req.version,
            wanted = wanted,
        ).group(Ipp.GROUP_OPERATION)
            .charset("attributes-charset", "utf-8")
            .language("attributes-natural-language", "en")

    private fun statusOnly(req: Ipp.Request, status: Int): ByteArray =
        baseWriter(req, status).build()

    private fun errorResponse(status: Int, requestId: Int): ByteArray =
        Ipp.Writer(status = status, requestId = requestId)
            .group(Ipp.GROUP_OPERATION)
            .charset("attributes-charset", "utf-8")
            .language("attributes-natural-language", "en")
            .build()

    /**
     * 当前 USB 打印机的型号名（如「联想 LJ2600D」）。找不到设备时给通用名。
     * IPP 属性与 mDNS TXT 里的 `ty` 共用这个来源，保证两边说法一致。
     */
    private fun modelName(): String {
        val dev = UsbPrinter.findSupportedPrinter(context) ?: return "Brother 激光打印机"
        return UsbPrinter.modelOf(context, dev)?.displayName ?: "Brother 激光打印机"
    }

    /**
     * 网络侧显示的打印机名。
     *
     * 与 mDNS 服务实例名**同源**（[AppSettings.effectivePrinterName]）。不同源的话，
     * iOS 列表里叫「客厅打印机」、点进去的打印面板显示 Cprinter，用户会以为加错了机器。
     *
     * 每次调用都读配置而不缓存：改名后要求立刻生效，而 SharedPreferences 已经在
     * 内存里，读一次只是一个 map 查找。
     */
    private fun printerDisplayName(): String =
        AppSettings.load(context).effectivePrinterName()

    /** IEEE 1284 风格的 device-id。型号部分随设备走，命令集固定。 */
    private fun deviceIdString(): String {
        val dev = UsbPrinter.findSupportedPrinter(context)
        val mdl = if (dev != null) {
            UsbPrinter.modelOf(context, dev)?.displayName?.replace(" ", "") ?: "BrotherLaser"
        } else {
            "BrotherLaser"
        }
        // CMD 必须说实话：直通模式没有 URF 编码器，写 URF 只会让客户端挑错格式。
        val gateway = UsbPrinter.driverOf(context) ==
            com.cawzen.printserver.usb.PrinterModel.Driver.PASSTHROUGH
        return if (gateway) "MFG:Generic;MDL:$mdl;CMD:PCL,PS,PDF;"
        else "MFG:Brother;MDL:$mdl;CMD:PCL,URF,PDF;"
    }

    /** 打印机属性。客户端（尤其 Windows）靠这些字段判断「能不能打印、怎么打」。 */
    private fun printerAttributes(req: Ipp.Request, requestHost: String = ""): ByteArray {
        // 客户端点名要什么就只回什么（RFC 8011 §4.2.5）。空列表或含 `all`
        // 表示全量。过滤的意义见 Ipp.Writer.wanted 的注释：iOS 只要 4 项时
        // 以前也会收到 7000 字节、含全部 collection 的报文，任一项解析失败
        // 整份作废，而且它不会告诉你失败在哪一项。
        val asked = req.requestedAttributes()
        val wanted: Set<String>? =
            if (asked.isEmpty() || asked.any { it.equals("all", ignoreCase = true) }) null
            else asked.toSet()
        val w = baseWriter(req, wanted = wanted)
        val opts = optionsProvider()
        val busy = jobs.queuedCount() > 0
        val idle = !busy
        // 直通 vs 渲染：能力自述必须跟着走，见下面 document-format 那一段。
        val gateway = UsbPrinter.driverOf(context) ==
            com.cawzen.printserver.usb.PrinterModel.Driver.PASSTHROUGH

        // `printer-uri-supported` 要回客户端**能认出来的那个地址**。
        //
        // AirPrint 客户端是从 mDNS 的 SRV/A 拼出 URI 的，而且它拼出来的主机名
        // 常带一个结尾的点（`ipp://wprint-b4f0.local.:8631/ipp/print` —— 日志里
        // 就是这么写的）。我们以前一律回 IPv4 字面量，后来又改成按 Host 头回显，
        // 两种都可能和客户端自己用的 URI 差一个字符。严格的客户端（iOS 的 PrintKit）
        // 一旦认为「我找到的这台」和「它自称的这台」不是同一个对象，就会读完属性
        // 停在那里、一个作业也不提交 —— 现象正是「看得见、点下去没反应」。
        //
        // 所以正确做法是 RFC 8011 §5.4.3 的原话：把**请求里 printer-uri 的值原样
        // 放在第一位**，再补上 Host 头形式和 IP 形式当兜底。
        val hostFromRequest = requestHost.substringBefore(':').trim()
        val ip = localIp()
        val primaryHost = hostFromRequest.ifBlank { ip }

        val uris = buildList {
            val asked = req.operationString("printer-uri")?.trim().orEmpty()
            if (asked.isNotEmpty()) add(asked)
            val byHost = "ipp://$primaryHost:$port/$RP"
            if (byHost !in this) add(byHost)
            val byIp = "ipp://$ip:$port/$RP"
            if (byIp !in this) add(byIp)
        }

        w.group(Ipp.GROUP_PRINTER)
        // 1setOf 的写法由 Writer 统一处理：**第一个值带属性名，后续值写空名**
        // （RFC 8010 §3.1.5）。这里以前的注释写成了「每条都带完整属性名」，
        // 是完全反的 —— 那个错误让 Android 默认打印服务一直看不到这台机器，
        // 详见 Ipp.Writer.attr() 上的注释。
        uris.forEach { w.uri("printer-uri-supported", it) }
        // RFC 8011 §5.4.3/§5.4.4：这两个属性是 1setOf，且**值个数必须与
        // printer-uri-supported 相同**（第 i 个值与第 i 个 URI 对应）。
        uris.forEach { w.keyword("uri-security-supported", "none") }
        uris.forEach { w.keyword("uri-authentication-supported", "none") }
        w
            .nameV("printer-name", printerDisplayName())
            .text("printer-info", "${printerDisplayName()} · ${modelName()} via Android USB")
            .text("printer-location", "Android / USB")
            // 机型名不要带自造的修饰。iOS 会拿它去匹配内置的机型/Rendering 配置，
            // 写成 "Lenovo LJ2600D (WPrint)" 这种混合串，匹配不上就只能落到通用配置，
            // 有些版本会因此把打印按钮判成不可用。真实型号最安全。
            .text("printer-make-and-model", modelName())
            // 稳定的 UUID：iOS 靠它记住这台机器的默认纸张/单双面。用 ANDROID_ID
            // 派生而不是随机生成，App 重启/重装后设置才不会丢。
            .uri("printer-uuid", "urn:uuid:${com.cawzen.printserver.mdns.AirPrintTxt.printerUuid(context)}")
            .enumV("printer-state", if (idle) Ipp.PRINTER_STATE_IDLE else Ipp.PRINTER_STATE_PROCESSING)
            .keyword("printer-state-reasons", "none")
            .bool("printer-is-accepting-jobs", true)
            .int("queued-job-count", jobs.queuedCount())
            // 默认格式必须写 image/urf。写 application/pdf 的话，iOS 会认定
            // 「这台机器要 PDF」，而 iOS 的打印管线**只会生成 URF**、不会生成 PDF，
            // 于是它在面板里把打印按钮判成不可用 —— 打印机还留在列表里，点下去
            // 却什么也不发。这是「看得见、点不动」最典型的一种成因。
        // 直通与渲染的自述必须彻底分开。混着报（既写 image/urf 又写 postscript）
        // 看着更全能，实际会让客户端挑一个我们处理不了的格式送过来：
        //   · 渲染模式产不出 PostScript（HL-2240D 没有 PS 解释器）；
        //   · 直通模式产不出 URF/PWG 光栅（不知道对面是什么机芯）。
        if (gateway) {
            w.mime("document-format-default", "application/vnd.hp-pcl")
                .mime("document-format-supported", "application/vnd.hp-pcl")
                .mime("document-format-supported", "application/postscript")
                .mime("document-format-supported", "application/pdf")
                .mime("document-format-supported", "application/octet-stream")
        } else {
            w.mime("document-format-default", "image/urf")
                .mime("document-format-supported", "image/urf")
                .mime("document-format-supported", "application/pdf")
                .mime("document-format-supported", "application/vnd.hp-pcl")
                .mime("document-format-supported", "application/octet-stream")
                .mime("document-format-supported", "image/pwg-raster")
                .keywordList("pwg-raster-document-type-supported", listOf("sgray_8", "black_1"))
                .keywordList("pwg-raster-document-resolution-supported", listOf("600dpi"))
                .keyword("pwg-raster-document-sheet-back", "normal")
                .keywordList("urf-supported", com.cawzen.printserver.mdns.AirPrintTxt.URF_TOKENS)
        }
        w.charset("charset-configured", "utf-8")
            .charset("charset-supported", "utf-8")
            .language("natural-language-configured", "en")
            // RFC 8011 §5.4 把这一条列为 **Printer Description 的必需属性**。
            // 缺了它，严格的客户端（iOS 的 PrintKit 就是）会认为这台的描述不完整，
            // 表现不是报错而是「反复重读属性、但从不提交作业」。
            .language("generated-natural-language-supported", "en")
            .enumList(
                "operations-supported", listOf(
                    Ipp.OP_PRINT_JOB, Ipp.OP_VALIDATE_JOB,
                    Ipp.OP_CREATE_JOB, Ipp.OP_SEND_DOCUMENT,
                    Ipp.OP_CANCEL_JOB, Ipp.OP_GET_JOB_ATTRIBUTES, Ipp.OP_GET_JOBS,
                    Ipp.OP_GET_PRINTER_ATTRIBUTES,
                    Ipp.OP_CLOSE_JOB,
                ),
            )
            .keywordList("ipp-versions-supported", listOf("1.1", "2.0"))
            .bool("multiple-document-jobs-supported", false)
            .keyword("pdl-override-supported", "attempted")
            .keyword("compression-supported", "none")
            .keywordList(
                "media-supported",
                listOf("iso_a4_210x297mm", "na_letter_8.5x11in", "na_legal_8.5x14in"),
            )
            .keyword("media-default", "iso_a4_210x297mm")
            .keyword("media-ready", "iso_a4_210x297mm")
            .keywordList("sides-supported", listOf("one-sided", "two-sided-long-edge"))
            .keyword("sides-default", if (opts.duplex) "two-sided-long-edge" else "one-sided")
            // 方向由客户端在光栅化时应用，我们只声明「接受这些值」——
            // 不声明的话 iOS 的打印面板不会出现方向选项。
            .enumList("orientation-requested-supported", listOf(3, 4, 5, 6))
            .enumV("orientation-requested-default", 3)
            .keywordList("print-color-mode-supported", listOf("monochrome"))
            .keyword("print-color-mode-default", "monochrome")
            // 同样只有 600。声明 300 会让 AirPrint/Windows 派 300dpi 的光栅过来，
            // 而这台机芯的光栅只有 600 一档（原因见 AirPrintTxt 的 RS600）。
            .resolutionList("printer-resolution-supported", listOf(600 to 600))
            .resolution("printer-resolution-default", 600, 600)
            .int("copies-default", 1)
            // 以前是 1..1，所以 iOS 的份数控件根本不出现（TXT 也诚实写了 Copies=F）。
            // 现在由 PCL 的 `ESC&l#X` 执行，打印机自己重复整个作业。
            .range(
                "copies-supported",
                com.cawzen.printserver.PrintOptions.MIN_COPIES,
                com.cawzen.printserver.PrintOptions.MAX_COPIES,
            )
            .text("printer-device-id", deviceIdString())
            .int("printer-up-time", ((System.currentTimeMillis() - startedAt) / 1000).toInt())
            // ---- 状态时间戳 ----
            //
            // 这几个不是「能收发作业」的必需项，但 PrintKit 用它们判断「我读到的这份
            // 能力描述是不是新鲜的」。全缺的话它只能反复重读 —— 日志里那 34 次
            // 同一组属性的请求，一部分就是这个原因。
            .int("printer-state-change-time", (startedAt / 1000).toInt())
            .int("printer-config-change-time", (startedAt / 1000).toInt())
            .dateTime("printer-state-change-date-time", startedAt)
            .dateTime("printer-config-change-date-time", startedAt)
            .dateTime("printer-current-time", System.currentTimeMillis())
            .text("printer-state-message", if (idle) "Ready" else "Printing")
            .int("pages-per-minute", PAGES_PER_MINUTE)
            // 耗材信息页（这颗硒鼓/粉盒的余量）—— 物理设备按 IPP Everywhere
            // 是 REQUIRED。指向我们自己的状态页，iOS 的「打印机信息」里点得开。
            .uri("printer-supply-info-uri", "http://$primaryHost:$port/")
            // ---- 明确「不支持」的能力 ----
            //
            // iOS 打开打印面板时会**逐条索要**下面这些（见日志里那组 33 项的请求）。
            // 不返回虽然在规范上等价于「不支持」，但 PrintKit 读不到就会一直重问。
            // 这里按各自的类型给出「空能力」的合法值，让它的能力表能收敛。
            .int("document-password-supported", 0)
            .bool("job-account-id-supported", false)
            .bool("landscape-orientation-requested-preferred", false)
            .keywordList("identify-actions-supported", listOf("none"))
            .keywordList("job-presets-supported", listOf("none"))
            // 不声明 JPEG 输入：这条管线只吃 URF / PWG / PCL / PDF。
            // 声明成 `none` 而不是干脆不写，是为了让客户端明确知道「别送 JPEG」。
            .keywordList("jpeg-features-supported", listOf("none"))
            .range("jpeg-k-octets-supported", 0, 0)
            .range("jpeg-x-dimension-supported", 0, 0)
            .range("jpeg-y-dimension-supported", 0, 0)
            // `printer-mandatory-job-attributes`（1setOf keyword）**必须声明**，
            // 值为 `none`。这里的语义是「客户端建作业时必须一起送的 Job Template
            // 属性名清单」；这台机器一项都不强制。
            //
            // 早先的注释认为「1setOf 表达不了空集，填 none 会被当成一个真实属性名
            // 要求客户端满足」，因此故意不写 —— 那是错的。RFC 8011 §4.2.1.10 与
            // `printer-state-reasons` 一样，用单值 `none` 表示空集，是规范定义的
            // 写法，不会被解析成属性名。
            //
            // 代价很具体：iOS 的 PrintKit 打开打印面板时会**单独**只要 4 项
            // （printer-state-reasons / media-source-supported / printer-input-tray /
            // printer-mandatory-job-attributes），缺任何一项就判定「这台机器状态
            // 不明」，面板关掉、一个作业都不提交 —— 现象就是 AirPrint「点了没反应」。
            // 这 4 项必须同时完整。
            .keywordList("printer-mandatory-job-attributes", listOf("none"))
            .keywordList(
                "job-creation-attributes-supported",
                listOf(
                    "copies", "media", "media-col", "orientation-requested", "print-color-mode",
                    "printer-resolution", "sides", "job-name", "document-format",
                ),
            )
            .keywordList("which-jobs-supported", listOf("completed", "not-completed", "all"))
            // ---- 作业模板属性 ----
            //
            // 这些不是「收发作业」的必需项，但 iOS 的「打印选项」面板是靠它们搭界面的。
            // 缺一整片的话，面板会空着、打印按钮也不激活 —— 表现同样是「点了没反应」。
            // 值都取「只有一种选择」的最小集合：能声明什么就得真能处理什么，
            // 声明了做不到的（比如份数）反而会打出错的纸。
            .bool("color-supported", false)
            .enumList("print-quality-supported", listOf(3, 4, 5))
            .enumV("print-quality-default", 4)
            .enumList("finishings-supported", listOf(3))
            .enumV("finishings-default", 3)
            .keywordList("output-bin-supported", listOf("face-down"))
            .keyword("output-bin-default", "face-down")
            .keywordList("job-sheets-supported", listOf("none"))
            .keyword("job-sheets-default", "none")
            // 客户端光栅化时的缩放策略。AirPrint 默认按 auto 处理，声明出来
            // 面板才会出现「缩放以适合」这类选项。
            .keywordList(
                "print-scaling-supported",
                listOf("auto", "auto-fit", "fill", "fit", "none"),
            )
            .keyword("print-scaling-default", "auto")
            // 进纸盒名要和 media-col 里的 media-source 对得上。真实打印机写的是
            // 具体纸盒（tray-1），只有进纸盒自动选择时才有 auto。两边不一致时，
            // 客户端会认为「报告的已装纸张不属于任何已知纸盒」。
            //
            // **这里刻意只留 tray-1，不再声明 auto。** 这台机器只有一个纸盒，
            // 而 `media-col-database` 里每一条的 media-source 都是 tray-1。
            // 同时声明 auto 会出现这样一种无解组合：客户端取「第一个 media-source」
            // 当默认（auto），再去 media-col-database 里找 `auto + A4` 的条目 ——
            // 找不到，于是判定「选的纸不在支持的纸张里」，把整个作业单判为无效，
            // 表现就是面板关掉、一个请求都不发。声明能力和纸张库必须严格同源。
            .keywordList("media-source-supported", listOf("tray-1"))
            .keyword("media-source-default", "tray-1")
            .keywordList("media-type-supported", listOf("stationery"))
            .keyword("media-type-default", "stationery")

        // ---- 结构化纸张描述（collection）----
        //
        // iOS 16 之后，打印面板的纸张模型主要从 `media-col-database` 搭起来：
        // 它要的是「尺寸 + 四边留白 + 进纸盒 + 介质类型」这一整套结构化数据，
        // 纯 keyword 的 `media-supported` 只算兜底。之前一条 media-col 都没给，
        // 面板就搭不出作业模板 —— 表现正是打印按钮**亮着**、按下去什么也不发，
        // 服务端日志里只有一串重复的 Get-Printer-Attributes。
        //
        // 三张纸与 `media-supported` 严格同源：一边有一边没有的纸张会在面板里变灰。
        //
        // 1setOf collection 的后续元素早年按「名字长度写 0」编码（RFC 8010 §3.1.6
        // 确实允许这种省略），但那依赖客户端严格按顺序读上下文；实测 iOS 只认到
        // 第一条，面板里只剩 A4。改成每条都写完整属性名 —— 字节层面等价于「多个
        // 同名属性」，是所有实现都接受的写法，不再赌客户端的宽容量。
        mediaEntries.forEach { m ->
            mediaCol(w, "media-col-database", m)
        }
        mediaCol(w, "media-col-ready", mediaEntries[0])
        mediaCol(w, "media-col-default", mediaEntries[0])
        w.keywordList(
            "media-col-supported",
            listOf(
                "media-key", "media-size", "media-size-name",
                "media-top-margin", "media-bottom-margin",
                "media-left-margin", "media-right-margin",
                "media-source", "media-type",
            ),
        )
        // 进纸盒状态。
        //
        // **`printer-input-tray` 不是 collection，是 octetString。** 这是本轮的白纸
        // 级错误：它的名字和 media-col 里的 tray 系列长得像同族，实际类型完全不同 ——
        // 值是**一串以 `;` 分隔的 `keyword=value` 文本**，一个托盘一个值。
        // 照 collection 编码（0x34 起始 + memberAttrName 成员）时，客户端在读到 tag
        // 的那一刻就失配，整份 Get-Printer-Attributes 响应被判无效。而 iOS 打开打印
        // 面板时恰好**单独**问这一项（连同 printer-state-reasons、media-source-supported、
        // printer-mandatory-job-attributes 共 4 项），拿不到有效答复就判定「这台机器
        // 状态不明」，面板直接关掉、一个作业都不提交 —— 正是这几轮的现象。
        //
        // 字段与取值来自 PWG 5100.13 §6.5 / ippsample 的参考实现（server/printer.c
        // 里就是一句 ippAddOctetString）。`name` 必须与 media-source-supported 里的
        // 关键字一致，这里同样是 tray-1。
        w.octets(
            "printer-input-tray",
            ("type=sheetFeedAutoRemovableTray;mediafeed=0;mediaxfeed=0;" +
                "maxcapacity=250;level=100;status=0;name=tray-1;").toByteArray(Charsets.UTF_8),
        )
        // 出纸口刻意不声明 `printer-output-tray`：它同样是 octetString，但 type 的
        // 合法取值（faceDown/faceUp 之外还有各厂商扩展）我手上没有权威对照，
        // 编错一个关键字比不声明更糟 —— 缺项只是「这项不支持」，编错是「整份无效」。
        // 出纸口能力已经由 output-bin-supported/default 表达。
        // PDF 单文档上限（单位 KB）。不声明的话部分客户端按「未知」处理，
        // 在提交前就把大文档判成超限。
        w.range("pdf-k-octets-supported", 0, 204800)
        w.uri("printer-more-info", "http://$primaryHost:$port/")

        // ---- IPP Everywhere（PWG 5100.14）的强制项 ----
        //
        // 下面这一组是「说自己是 IPP Everywhere 打印机」就必须有的。以前一项都没有，
        // 声明里也不自称 ipp-everywhere，等于对客户端说「我是台老式 IPP/1.1 机器」。
        // 新版 iOS / macOS 会按 IPP Everywhere 去校验，缺项时不给明确错误 ——
        // 表现就是面板里能选中、按钮是亮的，按下去一个作业都不发。
        //
        // `ipp-features-supported` 里写上 `ipp-everywhere` 是**主动声明**：
        // 客户端据此走「无驱动」路径，而不是去找并不存在的 PPD。
        //
        // 只声明 `ipp-everywhere`，**不声明 `airprint-2.0`**。后者不是 PWG 规范里
        // 的值，是 PAPPL/LPrint 那类实现的自定义标记，代表「支持 AirPrint 2.x 的
        // 一整套高级特性」（原件保留、覆盖页、耗材预估等）。声明一个自己没有实现的
        // 能力，客户端会据此给出用不了的选项，比不声明更糟。
        w.keywordList("ipp-features-supported", listOf("ipp-everywhere"))
        // 这台机器只能打印文档（不支持传真/扫描），这是 IPP Everywhere 的必填项。
        w.keywordList("printer-kind", listOf("document"))
        // 进纸方向：纸盒正面进纸，只有 portrait 一种。
        w.keywordList("feed-orientation-supported", listOf("portrait"))
        // 图标与本地化字符串。两者都是「客户端来取」的资源 URI，必须真的能取到 ——
        // 声明了 URI 却 404，比不声明更糟（客户端会当作资源获取失败）。
        val base = "http://$primaryHost:$port"
        w.uri("printer-icons", "$base/icon-48.png")
        w.uri("printer-icons", "$base/icon-128.png")
        w.uri("printer-icons", "$base/icon-512.png")
        w.language("printer-strings-languages-supported", "en")
        w.uri("printer-strings-uri", "$base/strings.json")
        // 耗材。`printer-supply` 和 `printer-input-tray` 是同一种编码方式：
        // octetString，值是一串 `key=value;` 文本（PWG 5100.13 §6.5）。
        // 碳粉盒是唯一的耗材，index=1 与 printer-supply-description 一一对应。
        w.octets(
            "printer-supply",
            ("index=1;class=supplyThatIsConsumed;type=toner;unit=percent;" +
                "maxcapacity=100;level=100;color=#000000;").toByteArray(Charsets.UTF_8),
        )
        w.text("printer-supply-description", "Black Toner Cartridge")
        // 注意：`printer-supply-info-uri` 在上面的打印机描述段已经发过一次
        // （line 853 附近）。同一属性在同一组里出现两次是协议错误，客户端
        // 会直接判整份报文非法 —— 所以这里**不能**再发一遍。

        return w.build()
    }

    /**
     * 纸张表：PWG 媒体名 + 尺寸（单位 1/100 mm）。
     *
     * 尺寸必须与 keyword 严格对应 —— iOS 会拿 `media-size` 算可打印区域，
     * 和名字对不上时那项纸张会被判无效。
     */
    private data class MediaEntry(val pwg: String, val w: Int, val h: Int)

    private val mediaEntries = listOf(
        MediaEntry("iso_a4_210x297mm", 21000, 29700),
        MediaEntry("na_letter_8.5x11in", 21590, 27940),
        MediaEntry("na_legal_8.5x14in", 21590, 35560),
    )

    /** LJ2600D 机芯四边不可打印区：12pt ≈ 4.23mm → 423（1/100 mm）。 */
    private val MEDIA_MARGIN = 423

    /** 标称打印速度（A4 单面连续打印）。面板上的「约 xx 页/分钟」用它。 */
    private val PAGES_PER_MINUTE = 26

    /**
     * 往报文里写一条 media-col collection。
     *
     * 编码按 RFC 8010 §3.1.6：`begCollection`(0x34) 起始，成员名用
     * `memberAttrName`(0x4A)，成员值紧跟其后且**名字长度为 0**，最后
     * `endCollection`(0x37)。嵌套的 `media-size` 也是一条 collection，
     * 它作为成员出现时，其 begCollection 的名字长度同样为 0。
     *
     * [name] 传空串表示这是 1setOf 的后续元素 —— 名字只在第一个元素上出现。
     */
    private fun mediaCol(w: Ipp.Writer, name: String, m: MediaEntry) {
        // `media-key` 是 media-col-database 条目的规范标识（IPP Everywhere 把它列为
        // 必需的成员）。格式是 `<尺寸>_<来源>_<类型>`，与 media-col 里那三个成员
        // 逐字对应 —— 客户端拿它做「这张纸选过了没有」的比对，缺了会让条目失去身份。
        val mediaKey = "${m.pwg}_tray-1_stationery"
        w.begCollection(name)
            .memberKeyword("media-key", mediaKey)
            .memberName("media-size")
            .begCollection("")
            .memberInt("x-dimension", m.w)
            .memberInt("y-dimension", m.h)
            .endCollection()
            .memberKeyword("media-size-name", m.pwg)
            .memberInt("media-bottom-margin", MEDIA_MARGIN)
            .memberInt("media-left-margin", MEDIA_MARGIN)
            .memberInt("media-right-margin", MEDIA_MARGIN)
            .memberInt("media-top-margin", MEDIA_MARGIN)
            .memberKeyword("media-source", "tray-1")
            .memberKeyword("media-type", "stationery")
            .endCollection()
    }

    private fun validateJob(req: Ipp.Request): ByteArray {
        val format = req.operationString("document-format") ?: "application/pdf"
        if (!formatSupported(format)) {
            val w = baseWriter(req, Ipp.STATUS_DOCUMENT_FORMAT_NOT_SUPPORTED)
            w.group(Ipp.GROUP_UNSUPPORTED).mime("document-format", format)
            return w.build()
        }
        return statusOnly(req, Ipp.STATUS_OK)
    }

    private fun printJob(req: Ipp.Request, docFile: File?): ByteArray {
        val format = req.operationString("document-format") ?: "application/octet-stream"
        val name = req.operationString("job-name") ?: "Untitled"
        val user = req.operationString("requesting-user-name") ?: ""

        if (docFile == null || docFile.length() == 0L) {
            log("Print-Job 收到空文档，拒绝")
            return statusOnly(req, Ipp.STATUS_BAD_REQUEST)
        }

        // 看字节，不看声明。声明 application/pdf 却送 PostScript 的情况真实存在，
        // 而把 PCL 丢进 PdfRenderer 只会得到「文档损坏」这种没有信息量的报错。
        val kind = sniffSupported(docFile, format) ?: return formatNotSupported(req, format)

        val job = jobs.submit(
            name = name,
            user = user,
            format = format,
            kind = kind,
            documentFile = docFile,
            duplex = req.requestedDuplex(),
            copies = req.requestedCopies(),
        )
        log(
            "接受作业 #${job.id}「${job.name}」${job.file.length()} 字节" +
                "（${com.cawzen.printserver.print.DocumentSniffer.describe(kind)}）" +
                "sides=${req.sidesText() ?: "未声明→按单面"} copies=${job.copies}"
        )

        val w = baseWriter(req)
        w.group(Ipp.GROUP_JOB)
            .uri("job-uri", jobUri(job.id))
            .int("job-id", job.id)
            .enumV("job-state", job.state)
            .keyword("job-state-reasons", job.reasons)
            .text("job-state-message", "queued")
            .nameV("job-name", job.name)
            .int("job-impressions", 0)
            .int("job-impressions-completed", 0)
        return w.build()
    }

    /**
     * Create-Job：只建作业号，文档等 Send-Document 送来。
     *
     * iOS 走的就是这条：先 Create-Job 拿 job-id，再 Send-Document 送 URF，
     * 最后 Close-Job。整条链路里我们只在 Send-Document 那一刻才碰磁盘。
     */
    private fun createJob(req: Ipp.Request): ByteArray {
        val name = req.operationString("job-name") ?: "Untitled"
        val user = req.operationString("requesting-user-name") ?: ""
        val format = req.operationString("document-format") ?: ""
        if (format.isNotBlank() && !formatSupported(format)) {
            return formatNotSupported(req, format)
        }
        val job = jobs.create(name, user, format, req.requestedDuplex(), req.requestedCopies())
        log(
            "Create-Job #${job.id}「${job.name}」format=${format.ifBlank { "-" }}" +
                " sides=${req.sidesText() ?: "未声明→按单面"} copies=${job.copies}（等文档）"
        )

        val w = baseWriter(req)
        w.group(Ipp.GROUP_JOB)
            .uri("job-uri", jobUri(job.id))
            .int("job-id", job.id)
            .enumV("job-state", job.state)
            .keyword("job-state-reasons", job.reasons)
            .text("job-state-message", "held for document")
            .nameV("job-name", job.name)
        return w.build()
    }

    /** Send-Document：把文档挂到 Create-Job 建好的作业上，落盘即排队。 */
    private fun sendDocument(req: Ipp.Request, docFile: File?): ByteArray {
        val id = jobIdOf(req) ?: return statusOnly(req, Ipp.STATUS_BAD_REQUEST)
        val job = jobs.get(id) ?: return statusOnly(req, Ipp.STATUS_NOT_FOUND)
        val format = req.operationString("document-format")
            ?: job.format.ifBlank { "application/octet-stream" }

        if (docFile == null || docFile.length() == 0L) {
            log("Send-Document 作业 #$id 没有文档数据，拒绝（空作业打不出东西）")
            return statusOnly(req, Ipp.STATUS_BAD_REQUEST)
        }
        val kind = sniffSupported(docFile, format) ?: return formatNotSupported(req, format)
        // 长度要在 attach 之前取：attach 会把文件 rename 进作业目录，之后原路径就没了。
        val docBytes = docFile.length()
        if (!jobs.attach(id, docFile, kind, format)) {
            // 作业不存在 / 已经有文档（我们不支持一作业多文档）/ 搬文件失败。
            return statusOnly(req, Ipp.STATUS_NOT_POSSIBLE)
        }
        log("Send-Document 作业 #$id $docBytes 字节 → ${com.cawzen.printserver.print.DocumentSniffer.describe(kind)}")

        val w = baseWriter(req)
        w.group(Ipp.GROUP_JOB)
            .uri("job-uri", jobUri(id))
            .int("job-id", id)
            .enumV("job-state", job.state)
            .keyword("job-state-reasons", job.reasons)
            .text("job-state-message", "queued")
            .nameV("job-name", job.name)
        return w.build()
    }

    /** Close-Job：文档在 Send-Document 时就已经排队了，这里只是确认收尾。 */
    private fun closeJob(req: Ipp.Request): ByteArray {
        val id = jobIdOf(req) ?: return statusOnly(req, Ipp.STATUS_BAD_REQUEST)
        val job = jobs.get(id) ?: return statusOnly(req, Ipp.STATUS_NOT_FOUND)
        log("Close-Job #$id（已有文档=${job.documentReady}）")
        return statusOnly(req, Ipp.STATUS_OK)
    }

    private fun jobAttributes(req: Ipp.Request): ByteArray {
        val id = req.operationValue("job-id")?.asInt()
            ?: req.operationString("job-uri")?.substringAfterLast('/')?.toIntOrNull()
            ?: return statusOnly(req, Ipp.STATUS_BAD_REQUEST)
        val job = jobs.get(id) ?: return statusOnly(req, Ipp.STATUS_NOT_FOUND)

        val w = baseWriter(req)
        w.group(Ipp.GROUP_JOB)
            .uri("job-uri", jobUri(job.id))
            .int("job-id", job.id)
            .enumV("job-state", job.state)
            .keyword("job-state-reasons", job.reasons)
            .text("job-state-message", job.message.take(255).ifBlank { "none" })
            .nameV("job-name", job.name)
            .nameV("job-originating-user-name", job.user)
            .mime("document-format", job.format)
            .int("job-printer-up-time", ((System.currentTimeMillis() - startedAt) / 1000).toInt())
            .int("time-at-creation", (job.createdAt / 1000).toInt())
            .int("job-impressions", job.impressions)
            .int("job-impressions-completed", if (job.state == Ipp.JOB_STATE_COMPLETED) job.impressions else 0)
            .keyword("job-printer-uri", printerUri())
        return w.build()
    }

    private fun getJobs(req: Ipp.Request): ByteArray {
        val all = jobs.snapshot().takeLast(20)
        val which = req.operationString("which-jobs") ?: "not-completed"
        val filtered = when (which) {
            "completed" -> all.filter { it.state >= Ipp.JOB_STATE_CANCELED }
            "not-completed" -> all.filter { it.state < Ipp.JOB_STATE_CANCELED }
            else -> all
        }
        val w = baseWriter(req)
        // 队列为空时不发任何作业组。发一个「空组」在 RFC 里没问题，
        // 但个别解析器会把它当成畸形报文，少一层风险。
        filtered.forEach { job ->
            w.group(Ipp.GROUP_JOB)
                .uri("job-uri", jobUri(job.id))
                .int("job-id", job.id)
                .enumV("job-state", job.state)
                .keyword("job-state-reasons", job.reasons)
                .nameV("job-name", job.name)
                .nameV("job-originating-user-name", job.user)
        }
        return w.build()
    }

    private fun cancelJob(req: Ipp.Request): ByteArray {
        val id = req.operationValue("job-id")?.asInt()
            ?: req.operationString("job-uri")?.substringAfterLast('/')?.toIntOrNull()
            ?: return statusOnly(req, Ipp.STATUS_BAD_REQUEST)
        return if (jobs.cancel(id)) statusOnly(req, Ipp.STATUS_OK)
        else statusOnly(req, Ipp.STATUS_NOT_POSSIBLE)
    }

    /**
     * 嗅探文档类型 + 判定是否支持。
     *
     * 只需要开头 1KB（PCL 探测要在前 1KB 里找 @PJL），不必把 GB 级文件读进来。
     * 不支持时返回 null 并记好日志 —— 调用方据此回 `0x040A document-format-not-supported`。
     */
    private fun sniffSupported(
        docFile: File,
        format: String,
    ): com.cawzen.printserver.print.DocumentSniffer.Kind? {
        val headBytes = docFile.inputStream().use { readUpTo(it, 1024) }
        val kind = com.cawzen.printserver.print.DocumentSniffer.sniff(headBytes)
        if (kindSupported(kind)) return kind

        val hexHead = headBytes.copyOfRange(0, minOf(16, headBytes.size))
            .joinToString(" ") { "%02X".format(it) }
        log(
            "文档类型不支持：${com.cawzen.printserver.print.DocumentSniffer.describe(kind)}" +
                "（声明的 format=$format，${docFile.length()} 字节，头 16 字节：$hexHead）"
        )
        return null
    }

    private fun kindSupported(kind: com.cawzen.printserver.print.DocumentSniffer.Kind): Boolean =
        kind == com.cawzen.printserver.print.DocumentSniffer.Kind.PDF ||
            kind == com.cawzen.printserver.print.DocumentSniffer.Kind.PCL ||
            kind == com.cawzen.printserver.print.DocumentSniffer.Kind.PWG_RASTER ||
            kind == com.cawzen.printserver.print.DocumentSniffer.Kind.URF

    private fun formatNotSupported(req: Ipp.Request, format: String): ByteArray {
        val w = baseWriter(req, Ipp.STATUS_DOCUMENT_FORMAT_NOT_SUPPORTED)
        w.group(Ipp.GROUP_UNSUPPORTED).mime("document-format", format)
        return w.build()
    }

    private fun jobIdOf(req: Ipp.Request): Int? =
        req.operationValue("job-id")?.asInt()
            ?: req.operationString("job-uri")?.substringAfterLast('/')?.toIntOrNull()

    private fun formatSupported(format: String): Boolean =
        format.equals("application/pdf", true) ||
            format.equals("application/octet-stream", true) ||
            format.equals("image/pwg-raster", true) ||
            // AirPrint 的格式。大小写不敏感地比 —— 有客户端发 image/URF。
            format.equals("image/urf", true) ||
            format.equals("application/vnd.hp-pcl", true) ||
            format.equals("application/vnd.hp-pcl5", true) ||
            // 通用直通要用的两种。PostScript 与 PCL6（PCLXL）本来就在「原样转发」
            // 的覆盖范围内，白名单不放行的话，客户端在提交阶段就被拒了，
            // 连字节都到不了嗅探器 —— 那才是真正可惜的一步。
            format.equals("application/postscript", true) ||
            format.equals("application/vnd.hp-pclxl", true)

    private fun jobUri(id: Int) = "ipp://${localIp()}:$port/${RP}/$id"

    // ── 原始端口状态诊断 ────────────────────────────────────────────────────

    /**
     * 把打印机报告的原始状态字节**按 USB Printer Class 1.0 §6.3.2 逐位解释**。
     *
     * 这里曾经并列两套互不相容的位解释（规范一套、IEEE 1284/CUPS 一套），指望靠
     * 「抽纸/装纸两次读数」去定标。那本身就是病：位定义只有一套，并列两套只会让
     * 谁都能挑一个顺眼的读数。实际踩到的正是这个 —— bit3（nFault）被当成缺纸，
     * 于是就绪态 `0x18` 被判成缺纸，所有作业在提交前就被拒。
     *
     * 现在只留规范定义，并把每个位的电平含义写清楚，读数即结论。
     */
    /**
     * 给打印机发 UEL，并报告前后的状态字节。
     *
     * UEL 是 PJL 的「退出当前语言、回到空闲态」。打印机在收到半截光栅流时
     * 会一直等 `ESC*b<n>W` 指定的剩余行数 —— 它的解析器没有超时自救，于是
     * 面板停在处理中、既不接受新作业也不走纸，按面板键也没反应。
     *
     * 这一条序列是**不拔电**唯一能把它拽出来的手段。所以做成一个按钮：
     * 排查时最需要的就是「先让机器回到已知状态」，而不是每次跑下去拔插。
     */
    private fun sendUelAndReport(): String {
        val dev = UsbPrinter.findSupportedPrinter(context)
            ?: return "未找到 USB 打印机（未连接或不支持）\n"
        val printer = UsbPrinter.open(context, dev)
            ?: return "打开失败：没有 USB 权限，或接口被占用（正在打印时请稍后再试）\n"
        return try {
            val before = printer.portStatusText()
            val ok = printer.sendUel()
            // 打印机处理 UEL 需要一点时间，读太早还是旧值。
            Thread.sleep(600)
            val after = printer.portStatusText()
            buildString {
                append("UEL 发送：${if (ok) "成功" else "失败"}\n")
                append("复位前：$before\n")
                append("复位后：$after\n")
                if (before == after) {
                    append("\n（前后状态相同。若持续缺纸/离线，请检查纸盒是否推到位、")
                    append("面板是否有卡纸或开盖提示。）\n")
                }
            }
        } catch (t: Throwable) {
            "异常：${t.javaClass.simpleName}: ${t.message}\n"
        } finally {
            printer.close()
        }
    }

    private fun usbDiagText(): String {
        val sb = StringBuilder()
        val dev = UsbPrinter.findSupportedPrinter(context)
        if (dev == null) {
            return "未找到 USB 打印机（未连接或不支持）\n"
        }
        sb.append("设备：${dev.deviceName}  VID=0x%04X PID=0x%04X  %s\n"
            .format(dev.vendorId, dev.productId, if (dev.productName != null) "\"${dev.productName}\"" else ""))
        val printer = UsbPrinter.open(context, dev)
        if (printer == null) {
            sb.append("打开失败：没有 USB 权限，或接口被占用（正在打印时请稍后再试）\n")
            return sb.toString()
        }
        try {
            val raw = printer.portStatus()
            if (raw < 0) {
                sb.append("GET_PORT_STATUS 无响应（-1）\n")
                return sb.toString()
            }
            val bits = Integer.toBinaryString(raw).padStart(8, '0')
            sb.append("原始状态字节：0x%02X  (二进制 %s)\n\n".format(raw, bits))

            sb.append("── 按 USB Printer Class 1.0 §6.3.2 解释 ──\n")
            // bit3 nFault（低有效）/ bit4 Select（高有效）/ bit5 PaperEmpty（高有效）。
            val spec = buildList {
                if (raw and 0x20 != 0) add("PaperEmpty（缺纸）")
                if (raw and 0x10 == 0) add("NotSelected（离线）")
                if (raw and 0x08 == 0) add("Fault（故障）")
            }
            sb.append(if (spec.isEmpty()) "  无异常位（无故障 / 在线 / 有纸）\n"
            else spec.joinToString("") { "  • $it\n" })

            sb.append("\n── 逐位 ──\n")
            for (i in 7 downTo 0) {
                val on = (raw shr i) and 1
                sb.append("  bit$i = $on\n")
            }
        } finally {
            printer.close()
        }
        return sb.toString()
    }

    /**
     * 画一张打印机图标（白底黑线），尺寸方形。
     *
     * 为什么要在运行时画而不是塞几张资源图：三种尺寸都要有，而 IPP Everywhere
     * 只要求「能取到合法 PNG」。现画省掉三份二进制资源，也避免图片和机型绑死。
     * 图形刻意极简（机身 + 出纸口 + 一张露出来的纸），缩到 48px 还能看出是打印机。
     */
    private fun iconPng(size: Int): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888,
        )
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.WHITE)
        val ink = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            isAntiAlias = true
            style = android.graphics.Paint.Style.FILL
        }
        val s = size.toFloat()
        // 上面露出的那张纸
        c.drawRect(s * 0.28f, s * 0.16f, s * 0.72f, s * 0.38f, ink)
        // 机身
        c.drawRoundRect(s * 0.14f, s * 0.36f, s * 0.86f, s * 0.74f, s * 0.06f, s * 0.06f, ink)
        // 出纸口（白）
        val out = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            isAntiAlias = true
        }
        c.drawRect(s * 0.30f, s * 0.62f, s * 0.70f, s * 0.80f, out)
        // 纸上的几行字（黑）
        val line = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            isAntiAlias = true
        }
        for (i in 0 until 3) {
            val y = s * (0.66f + i * 0.045f)
            c.drawRect(s * 0.34f, y, s * (0.66f - i * 0.06f), y + s * 0.014f, line)
        }
        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
        bmp.recycle()
        return bos.toByteArray()
    }

    /**
     * `printer-strings-uri` 指向的本地化字符串表。
     *
     * 格式按 PWG 5100.13 §10：外层键是语言标签，内层是「属性名 → 显示名」。
     * 只提供英文 —— `printer-strings-languages-supported` 里也只声明了 en，
     * 两边必须一致，多写一种却没有对应条目同样是自述矛盾。
     */
    private fun stringsJson(): String = buildString {
        append("{\n  \"en\": {\n")
        append("    \"printer-info\": \"${modelName()} via Android USB\",\n")
        append("    \"media-source.tray-1\": \"Tray 1\",\n")
        append("    \"media-type.stationery\": \"Plain Paper\",\n")
        append("    \"printer-supply.1\": \"Black Toner Cartridge\"\n")
        append("  }\n}\n")
    }

    // ── 状态页 ──────────────────────────────────────────────────────────────

    /**
     * 浏览器打开 http://手机IP:8631/ 就能看到服务器状态。
     * 这不是装饰：mDNS 广播失败或客户端配错端口时，这是唯一能快速分辨
     * 「服务没起来」和「协议对不上」的手段。
     */
    fun statusHtml(): String {
        val opts = optionsProvider()
        val list = jobs.snapshot().takeLast(10).reversed()
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        val rows = if (list.isEmpty()) {
            "<tr><td colspan=\"4\">暂无作业</td></tr>"
        } else {
            list.joinToString("") { j ->
                val state = when (j.state) {
                    Ipp.JOB_STATE_PENDING -> "排队"
                    Ipp.JOB_STATE_PROCESSING -> "打印中"
                    Ipp.JOB_STATE_CANCELED -> "已取消"
                    Ipp.JOB_STATE_ABORTED -> "失败"
                    Ipp.JOB_STATE_COMPLETED -> "完成"
                    else -> "状态${j.state}"
                }
                "<tr><td>#${j.id}</td><td>${esc(j.name)}</td><td>$state</td>" +
                    "<td>${fmt.format(Date(j.createdAt))}</td></tr>"
            }
        }
        return """
            <!doctype html><html lang="zh"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>${esc(printerDisplayName())} IPP</title>
            <style>
              body{font:15px/1.6 system-ui,sans-serif;margin:0;padding:24px;background:#faf7fd;color:#1c1b1f}
              h1{font-size:20px;margin:0 0 4px}
              .sub{color:#49454f;font-size:13px;margin-bottom:20px}
              .card{background:#fff;border:1px solid #e7e0ec;border-radius:12px;padding:16px;margin-bottom:16px}
              code{background:#f2ecf6;padding:2px 6px;border-radius:6px;font-size:13px}
              table{border-collapse:collapse;width:100%;font-size:13px}
              th,td{text-align:left;padding:6px 8px;border-bottom:1px solid #eee}
              th{color:#49454f;font-weight:600}
              .ok{color:#0a7c42;font-weight:600}
            </style></head><body>
            <h1>${esc(printerDisplayName())}</h1>
            <div class="sub">${modelName()} · Android USB · 无 Root</div>

            <div class="card">
              <div>运行状态：<span class="ok">${if (running) "监听中" else "已停止"}</span></div>
              <div>IPP 地址：<code>${printerUri()}</code></div>
              <div>HTTP 地址：<code>${httpUri()}</code></div>
              <div>当前设置：<code>${opts.describe()}</code></div>
              <div>打印机状态：<code>${esc(jobs.lastPortStatus)}</code></div>
              <div>支持格式：PDF / PCL 直通 / PWG Raster / URF（AirPrint）</div>
              <div>队列中：${jobs.queuedCount()} 个作业</div>
            </div>

            <div class="card">
              <div style="font-weight:600;margin-bottom:8px">最近 10 个作业</div>
              <table>
                <tr><th>ID</th><th>名称</th><th>状态</th><th>收到时间</th></tr>
                $rows
              </table>
            </div>

            <div class="sub" style="margin:0">
              Windows：添加打印机 →「按 URL 查找」→ 填上面的 IPP 地址。<br>
              iPhone / iPad：直接打印，在打印面板里选「${esc(printerDisplayName())}」（AirPrint，不用填地址）。
            </div>
            </body></html>
        """.trimIndent()
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")
}
