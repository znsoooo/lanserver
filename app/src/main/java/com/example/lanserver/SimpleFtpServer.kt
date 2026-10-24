package com.example.lanserver

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket

/**
 * 轻量 FTP 服务器（匿名登录、被动/主动模式、LIST/CWD/PWD/RETR/STOR/SIZE）。
 * 基于 SAF 选中的 DocumentFile 根目录。仅用于局域网内简易文件共享。
 */
class SimpleFtpServer(
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
                ClientConnection(ctx, sock, root, showHidden, allowWrite).start()
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

    private class ClientConnection(
        val ctx: Context,
        val sock: Socket,
        val root: DocumentFile,
        val showHidden: Boolean,
        val allowWrite: Boolean
    ) : Thread() {
        private val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
        private val writer = PrintWriter(OutputStreamWriter(sock.getOutputStream()), true)
        private var cwd: DocumentFile = root
        private var cwdRel = "/"
        private var pasvSocket: ServerSocket? = null
        private var portSocket: Socket? = null

        override fun run() {
            try {
                send("220 局域网服务器 FTP 已就绪。")
                while (true) {
                    val line = reader.readLine() ?: break
                    val sp = line.indexOf(' ')
                    val cmd = (if (sp < 0) line else line.substring(0, sp)).uppercase()
                    val arg = if (sp < 0) "" else line.substring(sp + 1).trim()
                    if (!handle(cmd, arg)) break
                }
            } catch (e: Exception) {
                // 连接异常，结束
            } finally {
                cleanup()
            }
        }

        private fun handle(cmd: String, arg: String): Boolean {
            when (cmd) {
                "USER" -> send("331 需要密码。")
                "PASS" -> send("230 登录成功。")
                "SYST" -> send("215 UNIX Type: L8")
                "FEAT" -> {
                    send("211-支持的扩展")
                    send(" UTF8")
                    send("211 结束")
                }
                "OPTS" -> send("200 OPTS $arg 成功。")
                "PWD", "XPWD" -> send("257 \"$cwdRel\" 是当前目录。")
                "CWD", "XCWD" -> {
                    val target = resolveRel(arg)
                    if (target != null && target.first.isDirectory) {
                        cwd = target.first
                        cwdRel = target.second
                        send("250 CWD 成功。")
                    } else {
                        send("550 $arg: 目录不存在。")
                    }
                }
                "CDUP" -> {
                    cwd = cwd.parentFile ?: root
                    cwdRel = parentRel(cwdRel)
                    send("250 CDUP 成功。")
                }
                "TYPE" -> send("200 类型已设为 $arg。")
                "NOOP" -> send("200 NOOP 成功。")
                "SIZE" -> {
                    val f = DocUtils.resolve(cwd, arg)
                    if (f != null && f.isFile) send("213 ${f.length()}") else send("550 文件不存在。")
                }
                "PASV" -> openPasv()
                "PORT" -> openPort(arg)
                "LIST", "NLST" -> listDir(arg)
                "RETR" -> retrieve(arg)
                "STOR" -> if (allowWrite) store(arg) else send("550 写入已禁用。")
                "MKD", "XMKD" -> if (allowWrite) makeDir(arg) else send("550 写入已禁用。")
                "RMD" -> if (allowWrite) removeDir(arg) else send("550 写入已禁用。")
                "DELE" -> if (allowWrite) deleteFile(arg) else send("550 写入已禁用。")
                "QUIT" -> {
                    send("221 再见。")
                    return false
                }
                else -> send("502 $cmd 未实现。")
            }
            return true
        }

        private fun resolveRel(arg: String): Pair<DocumentFile, String>? {
            val trimmed = arg.trimStart('/')
            if (trimmed.isEmpty() || trimmed == "/") return Pair(root, "/")
            var cur = root
            var rel = ""
            for (seg in trimmed.split('/')) {
                if (seg.isEmpty() || seg == ".") continue
                if (seg == "..") {
                    if (rel != "/") {
                        cur = cur.parentFile ?: root
                        rel = parentRel(rel)
                    }
                    continue
                }
                val next = cur.findFile(seg) ?: return null
                cur = next
                rel = if (rel == "/") "/$seg" else "$rel/$seg"
            }
            return Pair(cur, rel)
        }

        private fun parentRel(rel: String): String {
            if (rel == "/") return "/"
            val idx = rel.lastIndexOf('/')
            return if (idx <= 0) "/" else rel.substring(0, idx)
        }

        private fun openPasv() {
            try {
                pasvSocket = ServerSocket(0)
                val p = pasvSocket!!.localPort
                val addr = (sock.localAddress as? Inet4Address)?.hostAddress ?: "127.0.0.1"
                val parts = addr.split('.')
                val p1 = p / 256
                val p2 = p % 256
                send("227 进入被动模式 (${parts[0]},${parts[1]},${parts[2]},${parts[3]},$p1,$p2)。")
            } catch (e: Exception) {
                send("425 无法打开被动连接。")
            }
        }

        private fun openPort(arg: String) {
            try {
                val nums = arg.split(',').map { it.toInt() }
                val ip = "${nums[0]}.${nums[1]}.${nums[2]}.${nums[3]}"
                val p = nums[4] * 256 + nums[5]
                portSocket = Socket(ip, p)
                send("200 PORT 命令成功。")
            } catch (e: Exception) {
                send("425 无法打开数据连接。")
            }
        }

        private fun acceptData(): Socket? {
            return try {
                if (pasvSocket != null) {
                    val s = pasvSocket!!.accept()
                    try {
                        pasvSocket!!.close()
                    } catch (e: Exception) {
                    }
                    pasvSocket = null
                    s
                } else if (portSocket != null) {
                    val s = portSocket
                    portSocket = null
                    s
                } else null
            } catch (e: Exception) {
                null
            }
        }

        private fun listDir(arg: String) {
            val ds = acceptData()
            if (ds == null) {
                send("425 请先使用 PASV 或 PORT。")
                return
            }
            send("150 开始传输目录列表。")
            try {
                val pw = PrintWriter(ds.getOutputStream(), true)
                val target = if (arg.isNotEmpty()) resolveRel(arg)?.first ?: cwd else cwd
                for (f in DocUtils.listChildren(target, showHidden)) {
                    val name = f.name ?: "?"
                    val size = f.length()
                    val flag = if (f.isDirectory) "d" else "-"
                    pw.println("${flag}rwxr-xr-x 1 user user $size Jan 01 00:00 $name")
                }
                pw.flush()
            } catch (e: Exception) {
            } finally {
                try {
                    ds.close()
                } catch (e: Exception) {
                }
            }
            send("226 目录列表传输完成。")
        }

        private fun retrieve(arg: String) {
            val f = DocUtils.resolve(cwd, arg)
            if (f == null || !f.isFile) {
                send("550 文件不存在。")
                return
            }
            val ds = acceptData()
            if (ds == null) {
                send("425 请先使用 PASV 或 PORT。")
                return
            }
            send("150 正在以二进制模式发送 ${f.name}。")
            try {
                ctx.contentResolver.openInputStream(f.uri)?.use { input ->
                    ds.getOutputStream().use { out -> input.copyTo(out) }
                }
            } catch (e: Exception) {
            } finally {
                try {
                    ds.close()
                } catch (e: Exception) {
                }
            }
            send("226 传输完成。")
        }

        private fun store(arg: String) {
            val name = arg.trimStart('/').substringAfterLast('/')
            val target = if (name.isEmpty()) null
            else (cwd.findFile(name) ?: cwd.createFile("application/octet-stream", name))
            if (target == null || !target.isFile) {
                send("550 无法创建文件。")
                return
            }
            val ds = acceptData()
            if (ds == null) {
                send("425 请先使用 PASV 或 PORT。")
                return
            }
            send("150 可以开始接收数据。")
            try {
                ds.getInputStream().use { input ->
                    ctx.contentResolver.openOutputStream(target.uri)?.use { out -> input.copyTo(out) }
                }
            } catch (e: Exception) {
            } finally {
                try {
                    ds.close()
                } catch (e: Exception) {
                }
            }
            send("226 传输完成。")
        }

        private fun makeDir(arg: String) {
            val name = arg.trimStart('/').substringAfterLast('/')
            if (name.isEmpty()) {
                send("550 目录名无效。")
                return
            }
            val target = cwd.findFile(name) ?: cwd.createDirectory(name)
            if (target != null && target.isDirectory) {
                send("257 \"$name\" 目录已创建。")
            } else {
                send("550 无法创建目录。")
            }
        }

        private fun removeDir(arg: String) {
            val target = resolveRel(arg)
            if (target == null || !target.first.isDirectory) {
                send("550 目录不存在。")
                return
            }
            if (target.first.delete()) {
                send("250 目录已删除。")
            } else {
                send("550 无法删除目录。")
            }
        }

        private fun deleteFile(arg: String) {
            val f = DocUtils.resolve(cwd, arg)
            if (f == null || !f.isFile) {
                send("550 文件不存在。")
                return
            }
            if (f.delete()) {
                send("250 文件已删除。")
            } else {
                send("550 无法删除文件。")
            }
        }

        private fun send(msg: String) {
            writer.println(msg)
        }

        private fun cleanup() {
            try {
                pasvSocket?.close()
            } catch (e: Exception) {
            }
            try {
                portSocket?.close()
            } catch (e: Exception) {
            }
            try {
                sock.close()
            } catch (e: Exception) {
            }
        }
    }
}
