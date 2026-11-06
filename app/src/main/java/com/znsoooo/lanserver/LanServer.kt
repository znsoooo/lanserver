package com.znsoooo.lanserver

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections

/**
 * 单端口双协议服务器（HTTP + FTP 复用同一个 TCP 端口）。
 *
 * ## 为什么要合并
 * 两者都跑在 TCP 之上，区别只在应用层首行：
 *   - HTTP 请求行：`GET /path HTTP/1.1`
 *   - FTP 命令：   `USER anonymous`
 * 所以在 accept 之后读**第一行**就能可靠分流，客户端也就可以用同一个端口号
 * 分别以 `http://host:port` 和 `ftp://host:port` 访问。
 *
 * ## 关键坑：FTP 客户端不会先开口
 * 按 RFC 959，FTP 服务端要在连接建立后先发 `220` 问候，客户端收到后才发 `USER`。
 * 如果这里「一直等到有数据」再去判断，FTP 客户端会一直等 220 —— 双方僵死。
 * 因此采用**短探测**：最多等 [PROBE_TIMEOUT_MS]，期间
 *   - 收到数据 → 按首行判断是 HTTP 还是 FTP；
 *   - 超时没有数据 → 判为 FTP（正是「在等问候」的表现），交给 FTP 连接对象去发 220。
 *
 * 局域网内 HTTP 客户端的三次握手完成后会立刻写请求，首包通常远小于 300ms，
 * 因此这条路径不会有可感知的延迟；FTP 只在**建立控制连接**时多等这一个探测窗口。
 *
 * ## 并发
 * 每条连接独立线程，HTTP 与 FTP 并存互不影响。停止服务时统一关闭所有连接，
 * 使「改端口/改共享目录后重启」能立即断开旧会话。
 */
class LanServer(
    private val ctx: Context,
    private val port: Int,
    val config: ServerConfig
) : Thread() {

    @Volatile
    var running = false
        private set

    private var serverSocket: ServerSocket? = null

    /** 活动连接（含已交给下层的），停止时统一关闭。 */
    private val clients = Collections.synchronizedSet(HashSet<Socket>())

    override fun run() {
        try {
            serverSocket = ServerSocket(port).also { it.reuseAddress = true }
            running = true
            while (running) {
                val sock = try {
                    serverSocket!!.accept()
                } catch (e: Exception) {
                    break
                }
                if (!running) {
                    closeQuietly(sock)
                    break
                }
                // 顺带清理已结束的连接，避免集合长期增长
                synchronized(clients) { clients.removeAll { it.isClosed } }
                clients.add(sock)
                Thread { dispatch(sock) }.start()
            }
        } catch (e: Exception) {
            running = false
        }
    }

    /** 读首行 → 判断协议 → 交给对应的连接处理器。 */
    private fun dispatch(sock: Socket) {
        try {
            val input = sock.getInputStream()
            sock.soTimeout = PROBE_TIMEOUT_MS

            val first = try {
                input.read()
            } catch (e: SocketTimeoutException) {
                SILENT
            }

            if (first == -1) {
                // 对端连上就关闭（端口扫描等）
                closeQuietly(sock)
                return
            }
            sock.soTimeout = 0

            if (first == SILENT) {
                // 没有立即发数据：FTP 客户端在等 220，直接交给 FTP（由它发问候）
                SimpleFtpServer.ClientConnection(ctx, sock, config, {}, null).start()
                return
            }

            val head = readRestOfLine(input, first, MAX_LINE) ?: run {
                closeQuietly(sock)
                return
            }
            val text = String(head, Charsets.ISO_8859_1)

            // 非可打印 ASCII（例如 TLS ClientHello）既不是 HTTP 也不是 FTP，直接拒绝
            if (text.any { it.code < 0x20 && it.code != 0x09 || it.code > 0x7E }) {
                closeQuietly(sock)
                return
            }

            if (isHttpRequestLine(text)) {
                SimpleHttpServer.Connection(ctx, config, sock, text).start()
            } else {
                SimpleFtpServer.ClientConnection(ctx, sock, config, {}, head).start()
            }
        } catch (e: Exception) {
            closeQuietly(sock)
        }
    }

    /**
     * HTTP/1.x 请求行形如 `METHOD TARGET HTTP/1.1`。
     * FTP 的首条命令（USER/PASS/OPTS…）不会包含 `HTTP/x.y`，据此区分。
     */
    private fun isHttpRequestLine(line: String): Boolean =
        HTTP_LINE.matches(line.trim())

    private fun readRestOfLine(input: InputStream, first: Int, max: Int): ByteArray? {
        val buf = ByteArrayOutputStream(256)
        buf.write(first)
        var prev = first
        while (true) {
            val b = try {
                input.read()
            } catch (e: SocketTimeoutException) {
                return null
            }
            if (b < 0) return if (buf.size() == 0) null else buf.toByteArray()
            if (prev == '\r'.code && b == '\n'.code) {
                val arr = buf.toByteArray()
                return if (arr.isNotEmpty() && arr.last() == '\r'.code.toByte()) arr.copyOf(arr.size - 1) else arr
            }
            if (buf.size() >= max) return null
            buf.write(b)
            prev = b
        }
    }

    fun stopServer() {
        running = false
        closeQuietly(serverSocket)
        synchronized(clients) {
            clients.forEach { closeQuietly(it) }
            clients.clear()
        }
    }

    private fun closeQuietly(c: java.io.Closeable?) {
        try {
            c?.close()
        } catch (e: Exception) {
        }
    }

    companion object {
        /** 等待客户端首包的窗口（毫秒）。超时即判为 FTP。 */
        private const val PROBE_TIMEOUT_MS = 300
        private const val MAX_LINE = 8192

        /** 用 -2 表示「探测超时」，与 read() 的 -1（EOF）区分。 */
        private const val SILENT = -2

        private val HTTP_LINE = Regex("^[A-Z]{3,10}\\s+\\S+\\s+HTTP/\\d+\\.\\d+$")
    }
}
