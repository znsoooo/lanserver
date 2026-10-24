package com.example.lanserver

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream
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
    private val showHidden: Boolean,
    private val allowWrite: Boolean = false
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
                Connection(ctx, root, sock, showHidden, allowWrite).start()
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

    private class Connection(
        val ctx: Context,
        val root: DocumentFile,
        val sock: Socket,
        val showHidden: Boolean,
        val allowWrite: Boolean
    ) : Thread() {
        override fun run() {
            try {
                val input = sock.getInputStream()
                val out = sock.getOutputStream()
                val requestLine = readLine(input) ?: return
                // 请求行格式：METHOD PATH PROTOCOL，路径是第二个 token
                val parts = requestLine.split(' ')
                val method = if (parts.isNotEmpty()) parts[0].uppercase() else "GET"
                val rawPath = if (parts.size >= 2) parts[1] else "/"
                val decoded = try {
                    URLDecoder.decode(rawPath, "UTF-8")
                } catch (e: Exception) {
                    rawPath
                }

                // 读取请求头
                val headers = HashMap<String, String>()
                var contentLength = 0
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val key = line.substring(0, colon).trim().lowercase()
                        val value = line.substring(colon + 1).trim()
                        headers[key] = value
                        if (key == "content-length") contentLength = value.toIntOrNull() ?: 0
                    }
                }

                if (method == "POST") {
                    if (!allowWrite) {
                        send(out, 403, "text/plain; charset=utf-8", "403 Forbidden: 写入已禁用")
                        return
                    }
                    val ct = headers["content-type"] ?: ""
                    val boundary = if (ct.startsWith("multipart/form-data")) {
                        val b = ct.substringAfter("boundary=", "")
                        if (b.startsWith("\"")) b.substring(1, b.length - 1) else b
                    } else ""
                    if (boundary.isEmpty() || contentLength <= 0) {
                        send(out, 400, "text/plain; charset=utf-8", "400 Bad Request")
                        return
                    }
                    val body = readExact(input, contentLength)
                    val parent = DocUtils.resolve(root, decoded) ?: root
                    if (!parent.isDirectory) {
                        send(out, 400, "text/plain; charset=utf-8", "400 目标不是目录")
                        return
                    }
                    val uploaded = handleUpload(body, boundary, parent)
                    if (uploaded) {
                        // 上传成功后重定向回当前目录
                        val redirect = if (decoded == "/" || decoded.isEmpty()) "/" else "$decoded/"
                        sendRedirect(out, redirect)
                    } else {
                        send(out, 400, "text/plain; charset=utf-8", "400 未找到文件字段")
                    }
                    return
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

        /** 从输入流读取一行（CRLF 结尾），返回不含 CRLF 的字符串。 */
        private fun readLine(input: java.io.InputStream): String? {
            val baos = ByteArrayOutputStream(256)
            var prev: Int = -1
            while (true) {
                val b = input.read()
                if (b < 0) {
                    if (baos.size() == 0) return null
                    break
                }
                if (prev == '\r'.toInt() && b == '\n'.toInt()) break
                if (b != '\r'.toInt()) baos.write(b)
                prev = b
            }
            return baos.toString("UTF-8")
        }

        /** 从输入流读取指定长度的字节。 */
        private fun readExact(input: java.io.InputStream, n: Int): ByteArray {
            val baos = ByteArrayOutputStream(n)
            val buf = ByteArray(4096)
            var remaining = n
            while (remaining > 0) {
                val r = input.read(buf, 0, minOf(buf.size, remaining))
                if (r < 0) break
                baos.write(buf, 0, r)
                remaining -= r
            }
            return baos.toByteArray()
        }

        /** 解析 multipart/form-data，把文件保存到 parent 目录。 */
        private fun handleUpload(body: ByteArray, boundary: String, parent: DocumentFile): Boolean {
            val dashBoundary = "--$boundary".toByteArray(Charsets.UTF_8)
            var pos = 0
            var uploaded = false
            while (true) {
                val idx = body.indexOf(dashBoundary, pos)
                if (idx < 0) break
                val partStart = idx + dashBoundary.size
                if (partStart + 2 <= body.size && body[partStart] == '-'.toByte() && body[partStart + 1] == '-'.toByte()) {
                    break // 结束标记
                }
                // 跳过 \r\n
                var p = partStart
                if (p + 1 < body.size && body[p] == '\r'.toByte() && body[p + 1] == '\n'.toByte()) p += 2
                // 读取头部直到空行
                val headerEnd = body.indexOf("\r\n\r\n".toByteArray(Charsets.UTF_8), p)
                if (headerEnd < 0) break
                val headerText = body.copyOfRange(p, headerEnd).toString(Charsets.UTF_8)
                val cd = headerText.lines().firstOrNull { it.lowercase().startsWith("content-disposition:") } ?: ""
                val filename = parseFilename(cd)
                p = headerEnd + 4
                // 找下一个 boundary
                val nextIdx = body.indexOf(dashBoundary, p)
                if (nextIdx < 0) break
                var dataEnd = nextIdx - 2 // 去掉尾部 \r\n
                if (dataEnd < p) dataEnd = p
                if (filename != null && filename.isNotBlank()) {
                    val data = body.copyOfRange(p, dataEnd)
                    val safeName = filename.replace("/", "_")
                    val existing = parent.findFile(safeName)
                    val target = existing ?: parent.createFile("application/octet-stream", safeName)
                    if (target != null && target.isFile) {
                        ctx.contentResolver.openOutputStream(target.uri)?.use { it.write(data) }
                        uploaded = true
                    }
                }
                pos = nextIdx
            }
            return uploaded
        }

        private fun parseFilename(cd: String): String? {
            // Content-Disposition: form-data; name="file"; filename="xxx"
            val marker = "filename="
            val i = cd.indexOf(marker)
            if (i < 0) return null
            var rest = cd.substring(i + marker.length).trim()
            if (rest.startsWith("\"")) {
                rest = rest.substring(1)
                val q = rest.indexOf('"')
                if (q >= 0) rest = rest.substring(0, q)
            }
            return rest
        }

        private fun ByteArray.indexOf(needle: ByteArray, start: Int = 0): Int {
            if (needle.isEmpty()) return 0
            outer@ for (i in start..this.size - needle.size) {
                for (j in needle.indices) {
                    if (this[i + j] != needle[j]) continue@outer
                }
                return i
            }
            return -1
        }

        private fun sendRedirect(out: java.io.OutputStream, location: String) {
            val body = "<html><body>上传成功，<a href=\"${escapeHtml(location)}\">返回</a></body></html>"
            val bytes = body.toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 302 Found\r\n" +
                    "Location: $location\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
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
            sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>局域网服务器</title>")
            sb.append("<style>body{font-family:system-ui,-apple-system,sans-serif;background:#f2f5f9;color:#1b2430;margin:0;padding:16px}")
            sb.append(".card{background:#fff;border:1px solid #e4e9f0;border-radius:14px;padding:12px;margin-bottom:10px}")
            sb.append(".head{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:8px}")
            sb.append("h3{margin:0;font-size:16px;font-weight:600}")
            sb.append(".btn{background:#0ea5e9;color:#fff;border:0;border-radius:9px;padding:6px 12px;font-size:12px;font-weight:600}")
            sb.append(".btn:disabled{background:#9aa8b8}")
            sb.append("ul{list-style:none;padding:0;margin:8px 0 0}")
            sb.append("li{padding:8px 0;border-top:1px solid #e4e9f0}")
            sb.append("a{color:#0ea5e9;text-decoration:none;font-size:14px}")
            sb.append("</style></head><body>")
            sb.append("<div class=\"card\">")
            sb.append("<div class=\"head\"><h3>目录: ").append(escapeHtml(curPath)).append("</h3>")
            // 上传按钮：允许写入时显示表单
            if (allowWrite) {
                sb.append("<form method=\"POST\" enctype=\"multipart/form-data\" style=\"margin:0\">")
                    .append("<input type=\"file\" name=\"file\" required onchange=\"this.form.submit()\" id=\"up\" style=\"display:none\">")
                    .append("<button type=\"button\" class=\"btn\" onclick=\"document.getElementById('up').click()\">上传文件</button>")
                    .append("</form>")
            }
            sb.append("</div>")
            sb.append("<ul>")
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
            sb.append("</ul></div></body></html>")
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
