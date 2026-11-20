package com.cawzen.printserver.ipp

import android.content.Context
import android.util.Log
import com.cawzen.printserver.print.DocumentSniffer
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * RAW 打印端口（也叫「JetDirect / 9100 端口」）。
 *
 * ## 为什么要有它
 *
 * IPP 是标准路径，但 Windows 上「添加打印机 → 标准 TCP/IP 端口」的默认流程是
 * **RAW**：客户端把驱动生成的 PCL 字节流原样倒进 TCP 连接，不握手、不查询、
 * 不返回任何状态。这是不开 mDNS、不装 Bonjour 也能打印的最短路径 ——
 * 用户只需要在 Windows 里填「IP + 端口」。
 *
 * ## 为什么端口是 9101 而不是 9100
 *
 * Android 非 Root 应用绑不了 1024 以下的端口，9100 是标准的 raw 端口但在这里
 * 必然 `Permission denied`。所以默认用 **9101**，并在界面上把真实端口写清楚
 * （Windows 端口对话框里那一栏是可以随便填的）。绑定失败时向上试 9 个端口。
 *
 * ## 为什么 UNKNOWN 也照打
 *
 * RAW 的语义就是「客户端送什么，打印机就吃什么是它自己的事」。这里嗅探类型
 * 只是为了决定**是否需要重渲染**：PDF/PWG/URF 要走渲染管线，PCL 原样直通。
 * 嗅不出来时按 PCL 直通 —— Windows 厂商驱动送的就是 PCL，而把一段 PCL 当 PDF
 * 解析的结果是必然失败，反过来（PCL 当 PCL）最坏也只是打一张废纸。
 */
class RawServer(
    private val context: Context,
    private val jobs: JobStore,
    private val onLog: (String) -> Unit = {},
) {

    companion object {
        private const val TAG = "WPrint/Raw"

        /** 9100 绑不上（<1024），用 9101 作为缺省。 */
        const val DEFAULT_PORT = 9101

        private const val MAX_BYTES = 512L * 1024 * 1024
        private const val IDLE_TIMEOUT_MS = 120_000
        private const val SNIFF_BYTES = 1024
    }

    @Volatile
    var port: Int = DEFAULT_PORT
        private set

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var lastError: String = ""
        private set

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newFixedThreadPool(2)

    private val inbox: File = File(context.cacheDir, "raw_inbox").apply { mkdirs() }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        onLog("[RAW] $msg")
    }

    fun start(preferredPort: Int = DEFAULT_PORT): Boolean {
        if (running) return true
        lastError = ""
        var p = preferredPort
        while (p < preferredPort + 10) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(p), 8)
                serverSocket = s
                port = p
                running = true
                acceptThread = thread(name = "raw-accept", isDaemon = true) { acceptLoop(s) }
                log("RAW 端口已监听 0.0.0.0:$port")
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "端口 $p 绑定失败：${t.javaClass.simpleName} ${t.message}")
                p++
            }
        }
        lastError = "端口 $preferredPort..${preferredPort + 9} 全部绑定失败"
        log(lastError)
        return false
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { acceptThread?.interrupt() }
        acceptThread = null
        log("RAW 端口已停止")
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val socket = try {
                s.accept()
            } catch (t: Throwable) {
                if (!running) break
                Log.w(TAG, "accept 失败：${t.message}")
                continue
            }
            runCatching { pool.execute { handle(socket) } }
                .onFailure {
                    Log.w(TAG, "线程池拒绝连接：${it.message}")
                    runCatching { socket.close() }
                }
        }
    }

    private fun handle(socket: Socket) {
        val ip = socket.inetAddress?.hostAddress ?: "?"
        // 局部 val 而不是 var：finally 里要删它，捕获可变变量会让智能转换失效。
        val target = File(inbox, "${System.currentTimeMillis()}-${Thread.currentThread().id}.raw")
        try {
            socket.soTimeout = IDLE_TIMEOUT_MS
            socket.tcpNoDelay = true
            val head = ByteArray(SNIFF_BYTES)
            var headLen = 0
            var total = 0L

            socket.getInputStream().use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        if (headLen < SNIFF_BYTES) {
                            val copy = minOf(n, SNIFF_BYTES - headLen)
                            System.arraycopy(buf, 0, head, headLen, copy)
                            headLen += copy
                        }
                        out.write(buf, 0, n)
                        total += n
                        if (total > MAX_BYTES) {
                            throw IllegalStateException("作业超过 ${MAX_BYTES / 1024 / 1024}MB 上限")
                        }
                    }
                }
            }

            if (total == 0L) {
                log("来自 $ip 的连接没有数据，忽略（端口探测）")
                target.delete()
                return
            }

            var kind = DocumentSniffer.sniff(head.copyOf(headLen))
            if (kind == DocumentSniffer.Kind.UNKNOWN || kind == DocumentSniffer.Kind.POSTSCRIPT) {
                // 见类注释：RAW 通道一律按打印机原生数据直通。
                log("嗅探为 ${DocumentSniffer.describe(kind)}，按 PCL 直通处理")
                kind = DocumentSniffer.Kind.PCL
            }
            val job = jobs.submit(
                name = "RAW ${ip}",
                user = "raw",
                format = "application/octet-stream",
                kind = kind,
                documentFile = target,
                duplex = null,
                copies = null,
                client = ip,
            )
            ClientRegistry.touch(ip, "RAW 客户端")
            log("收到 RAW 作业 #${job.id}（${total} 字节，${DocumentSniffer.describe(kind)}）来自 $ip")
        } catch (t: Throwable) {
            Log.w(TAG, "RAW 连接处理失败：${t.javaClass.simpleName} ${t.message}")
            log("RAW 接收失败（$ip）：${t.message}")
        } finally {
            // submit 已经把文件搬进作业目录；这里删的是异常路径上的残留。
            runCatching { target.delete() }
            runCatching { socket.close() }
        }
    }

    /** 供诊断显示：端口是否真的在 listen。 */
    fun selfCheck(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 800) }
        true
    }.getOrDefault(false)
}
