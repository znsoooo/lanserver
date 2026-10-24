package com.example.lanserver

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * 轻量 HTTP 文件服务器（HTTP/1.0，单连接串行处理）。
 * 基于 SAF 选中的 DocumentFile 根目录，支持目录浏览与文件下载。
 */
class SimpleHttpServer(
    private val ctx: Context,
    private val root: DocumentFile,
    private val port: Int,
    private val showHidden: Boolean
) : Thread() {

    @Volatile
    var running = false
    private var serverSocket: ServerSocket? = null

    override fun run() {
        try {
            serverSocket = ServerSocket(port)
            running = true
            while (running) {
                val sock = serverSocket!!.accept()
                if (!running) {
                    sock.close()
                    break
                }
                Connection(ctx, root, sock, showHidden).start()
            }
        } catch (e: Exception) {
            running = false
        }
    }

    fun stopServer() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
        }
    }

    private class Connection(val ctx: Context, val root: DocumentFile, val sock: Socket, val showHidden: Boolean) : Thread() {
        override fun run() {
            try {
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val out = sock.getOutputStream()
                val requestLine = reader.readLine() ?: return
                // 请求行格式：METHOD PATH PROTOCOL，路径是第二个 token
                val parts = requestLine.split(' ')
                val rawPath = if (parts.size >= 2) parts[1] else "/"
                val decoded = try {
                    URLDecoder.decode(rawPath, "UTF-8")
                } catch (e: Exception) {
                    rawPath
                }
                val file = DocUtils.resolve(root, decoded)
                when {
                    file == null || !file.exists() -> send(out, 404, "text/plain; charset=utf-8", "404 Not Found")
                    file.isDirectory -> send(out, 200, "text/html; charset=utf-8", buildListing(decoded, file))
                    else -> {
                        val mime = DocUtils.mimeOf(file.name ?: "")
                        val header = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: $mime\r\n" +
                                "Content-Length: ${file.length()}\r\n" +
                                "Connection: close\r\n\r\n"
                        out.write(header.toByteArray(Charsets.UTF_8))
                        ctx.contentResolver.openInputStream(file.uri)?.use { it.copyTo(out) }
                    }
                }
            } catch (e: Exception) {
                // 客户端断开或解析失败，忽略
            } finally {
                try {
                    sock.close()
                } catch (e: Exception) {
                }
            }
        }

        private fun send(out: java.io.OutputStream, code: Int, mime: String, body: String) {
            val reason = if (code == 404) "Not Found" else "OK"
            val bytes = body.toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 $code $reason\r\n" +
                    "Content-Type: $mime\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        }

        private fun buildListing(curPath: String, dir: DocumentFile): String {
            val sb = StringBuilder()
            sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>局域网服务器</title></head><body>")
            sb.append("<h3>目录: ").append(escapeHtml(curPath)).append("</h3><ul>")
            // 父目录链接（根目录不显示）
            if (curPath != "/" && curPath.isNotEmpty()) {
                val parent = curPath.substringBeforeLast('/', "/")
                val parentLink = if (parent == "/") "/" else "$parent/"
                sb.append("<li><a href=\"").append(parentLink).append("\">../</a></li>")
            }
            // 关键点：使用“完整路径”的绝对链接（UTF-8 编码），
            // 否则浏览器会基于“当前目录”做相对解析，
            // 在目录 URL 没有结尾斜杠时把子文件夹一级丢掉（中文/英文子目录都受影响）。
            val base = if (curPath == "/") "/" else "$curPath/"
            for (f in DocUtils.listChildren(dir, showHidden)) {
                val name = f.name ?: "?"
                val enc = java.net.URLEncoder.encode(name, "UTF-8")
                val link = "$base$enc"
                sb.append("<li><a href=\"").append(link).append("\">")
                    .append(escapeHtml(name)).append(if (f.isDirectory) "/" else "").append("</a></li>")
            }
            sb.append("</ul></body></html>")
            return sb.toString()
        }

        private fun escapeHtml(s: String): String {
            return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
        }
    }
}
