package com.znsoooo.lanserver

import android.content.Context
import android.os.Build
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * MLSD / MLST 使用的时间格式：UTC，yyyyMMddHHmmss（RFC 3659）。
 */
private fun mlsTime(ms: Long): String {
    val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
    fmt.timeZone = TimeZone.getTimeZone("UTC")
    return fmt.format(Date(ms))
}

/**
 * 自研 FTP 服务器（严格遵循 RFC 959 / RFC 3659 的路径与目录语义）。
 *
 * 关于“为何不用 Apache FtpServer 等成熟库”：曾专门评估过业界最常用的 Apache FtpServer。
 * 反编译核实其控制通道编解码在 FtpServerProtocolCodecFactory 中把字符集**硬编码为 UTF-8**
 * （FtpResponseEncoder 的 CharsetEncoder 还是编译期常量），ListenerFactory / ConnectionConfigFactory
 * 均无字符集配置入口（1.1.1 与 1.2.0 皆如此）。而本项目所处的中文 Windows 资源管理器场景
 * **必须使用 ANSI(GBK)** 才能避免文件名乱码（已通过抓取客户端原始字节确证），两者直接冲突。
 * 故此处按 RFC 正确实现路径/目录语义，并保留已验证有效的编码方案。
 *
 * 编码策略：
 *  1. 控制通道响应一律英文标准消息，避免客户端按本地代码页解码出现乱码；
 *  2. 文件名/路径按字节“无损判定”：能严格按 UTF-8 解码就认定 UTF-8，否则认定 GB18030；
 *     LIST/MLSD 输出跟随同一编码，避免“按 A 解码、再按 B 编码”的往返丢字。
 */
class SimpleFtpServer(
    private val ctx: Context,
    private val port: Int,
    val config: ServerConfig
) : Thread() {

    @Volatile
    var running = false
    private var serverSocket: ServerSocket? = null

    /** 当前活动的客户端连接；停止服务时需一并断开。 */
    private val clients = Collections.synchronizedList(ArrayList<ClientConnection>())

    override fun run() {
        try {
            serverSocket = ServerSocket(port).also { it.reuseAddress = true }
            running = true
            while (running) {
                val sock = serverSocket!!.accept()
                if (!running) {
                    sock.close()
                    break
                }
                val c = ClientConnection(ctx, sock, config, onClosed = { clients.remove(it) })
                clients.add(c)
                c.start()
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
        val snapshot = synchronized(clients) { clients.toList() }
        for (c in snapshot) {
            try {
                c.shutdown()
            } catch (e: Exception) {
            }
        }
        synchronized(clients) { clients.clear() }
    }

    // ---------------------------------------------------------------------
    /**
     * 单个 FTP 控制连接。
     * 由 [LanServer] 在分流后创建（因此不再是 private）；[prefetched] 是协议判别时
     * 已经读出的第一行，避免重复读取导致首条命令丢失。
     */
    class ClientConnection(
        val ctx: Context,
        val sock: Socket,
        val config: ServerConfig,
        val onClosed: (ClientConnection) -> Unit = {},
        private var prefetched: ByteArray? = null
    ) : Thread() {

        private val rawInput = sock.getInputStream()
        private val rawOutput = sock.getOutputStream()

        /**
         * 本连接的“文件名”编码。默认 GB18030：本服务刻意不宣告 UTF8 扩展，中文 Windows
         * 资源管理器会以 ANSI(GBK) 收发文件名；若客户端确实发来合法 UTF-8 会在解码时改判。
         */
        private var liveCharset: Charset = gb()

        private var cwd: DocumentFile = config.root
        private var cwdRel = "/"
        /** 记录 cwd 所属的根，用于检测“共享目录被热更新”并重新锚定。 */
        private var cwdRoot: DocumentFile = config.root

        private var pasvSocket: ServerSocket? = null
        private var portSocket: Socket? = null

        /** REST 命令设置的续传偏移（字节）。 */
        private var restOffset = 0L
        /** RNFR 记录的待重命名源路径（相对根）。 */
        private var renameFromRel: String? = null

        private fun gb(): Charset =
            runCatching { charset("GB18030") }.getOrElse { StandardCharsets.UTF_8 }

        // ------------------------------------------------------------------
        override fun run() {
            try {
                send("220 LAN Server FTP ready.")
                while (true) {
                    val raw = readCommandLine() ?: break
                    val line = decodeLossless(raw)
                    val sp = line.indexOf(' ')
                    val cmd = (if (sp < 0) line else line.substring(0, sp)).uppercase().trim()
                    val arg = if (sp < 0) "" else line.substring(sp + 1).trim()
                    if (cmd.isEmpty()) continue
                    if (!handle(cmd, arg)) break
                }
            } catch (e: Exception) {
                // 连接异常，结束
            } finally {
                cleanup()
                onClosed(this)
            }
        }

        /** 停止/重启时由服务端调用：关闭控制连接与数据连接。 */
        fun shutdown() {
            closeQuietly(pasvSocket)
            closeQuietly(portSocket)
            try {
                sock.close()
            } catch (e: Exception) {
            }
        }

        // ------------------------------------------------------------------
        // 命令分发
        // ------------------------------------------------------------------
        private fun handle(cmd: String, arg: String): Boolean {
            refreshIfRootChanged()
            try {
                when (cmd) {
                    "USER" -> send("331 Password required.")
                    "PASS" -> send("230 Anonymous login ok.")
                    "SYST" -> send("215 UNIX Type: L8")
                    "FEAT" -> feat()
                    "OPTS" -> {
                        // 拒绝 UTF8 请求，维持客户端使用 ANSI(GBK)
                        if (arg.uppercase().startsWith("UTF8")) send("501 UTF8 not supported.")
                        else send("200 OPTS $arg ok.")
                    }
                    "PWD", "XPWD" -> send("257 \"$cwdRel\" is current directory.")
                    "CWD", "XCWD" -> doCwd(arg)
                    "CDUP", "XCUP" -> doCdup()
                    "TYPE" -> send("200 Type set to ${if (arg.isEmpty()) "I" else arg}.")
                    "MODE" -> send("200 Mode set to Stream.")
                    "STRU" -> send("200 Structure set to File.")
                    "ALLO" -> send("202 Allocation not needed.")
                    "NOOP" -> send("200 NOOP ok.")
                    "SIZE" -> doSize(arg)
                    "MDTM" -> doMdtm(arg)
                    "REST" -> doRest(arg)
                    "PASV" -> openPasv()
                    "EPSV" -> openEpsv()
                    "PORT" -> openPort(arg)
                    "LIST" -> listDir(arg, namesOnly = false)
                    "NLST" -> listDir(arg, namesOnly = true)
                    "MLSD" -> mlsd(arg)
                    "MLST" -> mlst(arg)
                    "RETR" -> retrieve(arg)
                    "STOR" -> if (config.allowWrite) store(arg, append = false)
                    else send("550 Write disabled.")
                    "APPE" -> if (config.allowWrite) store(arg, append = true)
                    else send("550 Write disabled.")
                    "MKD", "XMKD" -> if (config.allowWrite) makeDir(arg)
                    else send("550 Write disabled.")
                    "RMD", "XRMD" -> if (config.allowWrite) removeDir(arg)
                    else send("550 Write disabled.")
                    "DELE" -> if (config.allowWrite) deleteFile(arg)
                    else send("550 Write disabled.")
                    "RNFR" -> if (config.allowWrite) renameFrom(arg)
                    else send("550 Write disabled.")
                    "RNTO" -> if (config.allowWrite) renameTo(arg)
                    else send("550 Write disabled.")
                    "QUIT" -> {
                        send("221 Goodbye.")
                        return false
                    }
                    else -> send("502 Command not implemented.")
                }
            } catch (e: Exception) {
                // 单条命令异常不应中断整个会话
                send("550 Command failed.")
            }
            return true
        }

        private fun feat() {
            // 刻意“不”宣告 UTF8：中文 Windows 资源管理器在 UTF-8 模式下对文件名存在已知的
            // 有损转换缺陷（会把部分中文字节塌成 '?' 且无法还原），改用 ANSI(GBK) 模式才正常。
            send("211-Extensions supported:")
            send(" SIZE")
            send(" MDTM")
            send(" MLST type*;size*;modify*;")
            send(" MLSD")
            send(" REST STREAM")
            send("211 End")
        }

        // ------------------------------------------------------------------
        // 路径解析（RFC 959：参数以 “/” 开头为绝对路径，否则相对当前目录）
        // ------------------------------------------------------------------
        private class Resolved(val file: DocumentFile, val rel: String)

        private fun resolvePath(arg: String): Resolved? {
            val a = arg.trim()
            var cur: DocumentFile
            var rel: String
            val rest: String
            if (a.startsWith("/")) {
                cur = config.root
                rel = "/"
                rest = a
            } else {
                cur = cwd
                rel = cwdRel
                rest = a
            }
            for (seg in rest.split('/')) {
                when {
                    seg.isEmpty() || seg == "." -> {
                    }
                    seg == ".." -> {
                        if (rel != "/") {
                            val pr = parentRel(rel)
                            cur = resolveAbsolute(pr) ?: return null
                            rel = pr
                        }
                    }
                    else -> {
                        val next = findChild(cur, seg) ?: return null
                        rel = if (rel == "/") "/$seg" else "$rel/$seg"
                        cur = next
                    }
                }
            }
            return Resolved(cur, rel)
        }

        /** 从根按规范化相对路径解析（用于 “..” 与根变更后重锚）。 */
        private fun resolveAbsolute(rel: String): DocumentFile? {
            if (rel == "/" || rel.isEmpty()) return config.root
            var cur = config.root
            for (seg in rel.trimStart('/').split('/')) {
                if (seg.isEmpty() || seg == "." || seg == "..") continue
                cur = findChild(cur, seg) ?: return null
            }
            return cur
        }

        /**
         * 查找子项。
         *
         * 注意：**不再以 DocumentFile.findFile() 为主**。实测部分 ROM 的 findFile 不可靠——
         * 连刚创建的文件都会返回 null（日志里同一次连接反复 createFile 生成了 33 个副本即是证据），
         * 在“子项与父目录同名”（如 /lanserver.git/lanserver.git）时还可能返回错误对象，
         * 导致 RETR 判定成“不是文件”而回 550。
         *
         * 改为：一次批量查询取回本目录全部子项，再按 displayName 精确匹配；findFile/listFiles 仅兜底。
         * 这样每个路径段只多一次 IPC（与列目录同源），并天然避开同名歧义。
         */
        private fun findChild(dir: DocumentFile, name: String): DocumentFile? {
            // 快路径：findFile 若能返回“名字确实匹配”的对象，说明没有同名歧义，直接用。
            try {
                val f = dir.findFile(name)
                if (f != null && f.name == name) return f
            } catch (e: Exception) {
            }
            // 慢路径：一次批量查询后精确匹配（findFile 可能返回错误对象，如同名嵌套时返回父目录）
            try {
                val hit = DocUtils.listEntries(ctx, config.root, dir, true)
                    .firstOrNull { it.name == name }
                if (hit != null) return DocumentFile.fromSingleUri(ctx, hit.uri)
            } catch (e: Exception) {
            }
            // 最后兜底：遍历匹配（listFiles 的结果带真实名字，不存在同名歧义）
            return try {
                dir.listFiles().firstOrNull { it.name == name }
            } catch (e: Exception) {
                null
            }
        }

        private fun parentRel(rel: String): String {
            if (rel == "/" || rel.isEmpty()) return "/"
            val idx = rel.lastIndexOf('/')
            return if (idx <= 0) "/" else rel.substring(0, idx)
        }

        /** 共享目录被热更新时，重新锚定 cwd（尽量保留相对路径，失败则回到根）。 */
        private fun refreshIfRootChanged() {
            val r = config.root
            if (r === cwdRoot) return
            cwdRoot = r
            val re = if (cwdRel == "/") r else resolveAbsolute(cwdRel)
            if (re != null && re.isDirectory) {
                cwd = re
            } else {
                cwd = r
                cwdRel = "/"
            }
        }

        private class CreateTarget(
            val parent: DocumentFile,
            val name: String,
            val fullRel: String
        )

        /**
         * 为 MKD/STOR 这类“创建”操作解析目标：参数可能不含路径（相对当前目录），
         * 也可能形如 “/a/b/name” 或 “sub/name”。返回父目录、名称与目标绝对相对路径。
         */
        private fun splitForCreate(arg: String): CreateTarget? {
            val a = arg.trim().trimEnd('/')
            if (a.isEmpty() || a == "/") return null
            val idx = a.lastIndexOf('/')
            val name = if (idx < 0) a else a.substring(idx + 1)
            if (name.isEmpty() || name == "." || name == "..") return null
            val parentPart = when {
                idx < 0 -> ""
                idx == 0 -> "/"
                else -> a.substring(0, idx)
            }
            val parent: DocumentFile
            val prel: String
            if (parentPart.isEmpty()) {
                parent = cwd
                prel = cwdRel
            } else {
                val r = resolvePath(parentPart) ?: return null
                parent = r.file
                prel = r.rel
            }
            val fullRel = if (prel == "/") "/$name" else "$prel/$name"
            return CreateTarget(parent, name, fullRel)
        }

        // ------------------------------------------------------------------
        // 目录相关命令
        // ------------------------------------------------------------------
        private fun doCwd(arg: String) {
            if (arg.isEmpty()) {
                send("550 Failed to change directory.")
                return
            }
            val r = resolvePath(arg)
            val isDir = r != null && r.file.isDirectory
            if (isDir) {
                cwd = r!!.file
                cwdRel = r.rel
                send("250 CWD command successful.")
            } else {
                send("550 Failed to change directory.")
            }
        }

        private fun doCdup() {
            if (cwdRel != "/") {
                val pr = parentRel(cwdRel)
                val pf = resolveAbsolute(pr)
                if (pf != null) {
                    cwd = pf
                    cwdRel = pr
                }
            }
            send("250 CDUP command successful.")
        }

        private fun makeDir(arg: String) {
            val t = splitForCreate(arg) ?: run {
                send("550 Invalid directory name.")
                return
            }
            val target = findChild(t.parent, t.name) ?: t.parent.createDirectory(t.name)
            if (target != null && target.isDirectory) {
                // RFC 959：257 "<pathname>" —— 返回绝对路径，避免客户端据此再次用相对路径解析出错
                send("257 \"${t.fullRel}\" directory created.")
            } else {
                send("550 Cannot create directory.")
            }
        }

        private fun removeDir(arg: String) {
            val f = resolvePath(arg)?.file
            if (f == null || !f.isDirectory) {
                send("550 Directory not found.")
                return
            }
            if (f.delete()) send("250 Directory removed.") else send("550 Cannot remove directory.")
        }

        private fun doSize(arg: String) {
            val f = resolvePath(arg)?.file
            if (f != null && f.isFile) send("213 ${f.length()}") else send("550 File not found.")
        }

        private fun doMdtm(arg: String) {
            val f = resolvePath(arg)?.file
            if (f == null) {
                send("550 File not found.")
                return
            }
            val t = f.lastModified()
            if (t > 0) send("213 ${mlsTime(t)}") else send("550 Could not get modification time.")
        }

        private fun doRest(arg: String) {
            val n = arg.trim().toLongOrNull()
            if (n == null || n < 0) {
                send("501 Invalid restart offset.")
                return
            }
            restOffset = n
            send("350 Restarting at $n. Send STORE or RETRIEVE.")
        }

        private fun deleteFile(arg: String) {
            val f = resolvePath(arg)?.file
            if (f == null || !f.isFile) {
                send("550 File not found.")
                return
            }
            if (f.delete()) send("250 File removed.") else send("550 Cannot delete file.")
        }

        // ------------------------------------------------------------------
        // 重命名 / 移动
        //   · 同目录改名：SAF 原生 rename（DocumentsContract.renameDocument）——文件系统级，
        //     瞬间完成、不复制数据（大文件/大文件夹也一样）。
        //   · 跨目录移动：优先 DocumentsContract.moveDocument（API 24+）；
        //     个别 provider 不支持时，回退为「复制到目标 + 删除源」。
        // ------------------------------------------------------------------
        private fun renameFrom(arg: String) {
            val r = resolvePath(arg)
            val f = r?.file
            if (f == null || !f.exists()) {
                renameFromRel = null
                send("550 File or directory not found.")
                return
            }
            if (f === config.root || r.rel == "/") {
                renameFromRel = null
                send("550 Cannot rename root directory.")
                return
            }
            // 记录规范化的绝对相对路径，供 RNTO 判断“同目录改名”还是“跨目录移动”
            renameFromRel = r.rel
            send("350 Ready for RNTO.")
        }

        private fun renameTo(arg: String) {
            val fromRel = renameFromRel
            renameFromRel = null
            if (fromRel == null) {
                send("503 Bad sequence of commands (need RNFR first).")
                return
            }
            val src = resolveAbsolute(fromRel)
            if (src == null || !src.exists()) {
                send("550 Source file not found.")
                return
            }
            val t = splitForCreate(arg) ?: run {
                send("550 Invalid target name.")
                return
            }
            if (findChild(t.parent, t.name) != null) {
                send("550 Target already exists.")
                return
            }
            val ok = if (parentRel(fromRel) == parentRel(t.fullRel)) {
                // 同一目录内改名：文件系统级 rename，瞬间完成
                runCatching {
                    DocumentsContract.renameDocument(ctx.contentResolver, src.uri, t.name) != null
                }.getOrDefault(false)
            } else {
                moveAcross(src, t)
            }
            if (ok) send("250 Rename successful.") else send("550 Rename failed.")
        }

        /** 跨目录移动：优先系统 moveDocument（快、原子），不支持则回退「复制 + 删除」。 */
        private fun moveAcross(src: DocumentFile, t: CreateTarget): Boolean {
            if (Build.VERSION.SDK_INT >= 24) {
                try {
                    val srcParent = src.parentFile?.uri
                    if (srcParent != null) {
                        var moved = DocumentsContract.moveDocument(
                            ctx.contentResolver, src.uri, srcParent, t.parent.uri
                        )
                        // moveDocument 会保留原文件名；目标名不同时再改一次名
                        if (moved != null && src.name != t.name) {
                            moved = DocumentsContract.renameDocument(ctx.contentResolver, moved, t.name)
                        }
                        if (moved != null) return true
                    }
                } catch (e: Exception) {
                    // 落到下面的复制+删除兜底
                }
            }
            val copied = if (src.isDirectory) copyDirInto(src, t.parent, t.name)
            else copyFileInto(src, t.parent, t.name)
            return copied != null && src.delete()
        }

        private fun copyFileInto(src: DocumentFile, destDir: DocumentFile, newName: String): DocumentFile? {
            val mime = src.type ?: "application/octet-stream"
            val dst = destDir.createFile(mime, newName) ?: return null
            return try {
                ctx.contentResolver.openInputStream(src.uri)?.use { input ->
                    ctx.contentResolver.openOutputStream(dst.uri)?.use { out -> input.copyTo(out) }
                }
                dst
            } catch (e: Exception) {
                null
            }
        }

        private fun copyDirInto(src: DocumentFile, destDir: DocumentFile, newName: String): DocumentFile? {
            val dst = destDir.createDirectory(newName) ?: return null
            for (e in DocUtils.listEntries(ctx, config.root, src, showHidden = true)) {
                val child = findChild(src, e.name) ?: continue
                if (e.isDirectory) copyDirInto(child, dst, e.name) else copyFileInto(child, dst, e.name)
            }
            return dst
        }

        // ------------------------------------------------------------------
        // 数据连接
        // ------------------------------------------------------------------
        private fun openPasv() {
            closeQuietly(pasvSocket)
            try {
                pasvSocket = ServerSocket(0)
                val p = pasvSocket!!.localPort
                val addr = (sock.localAddress as? Inet4Address)?.hostAddress ?: "127.0.0.1"
                val parts = addr.split('.')
                val p1 = p / 256
                val p2 = p % 256
                send("227 Entering Passive Mode (${parts[0]},${parts[1]},${parts[2]},${parts[3]},$p1,$p2)")
            } catch (e: Exception) {
                send("425 Cannot open passive connection.")
            }
        }

        private fun openEpsv() {
            closeQuietly(pasvSocket)
            try {
                pasvSocket = ServerSocket(0)
                send("229 Entering Extended Passive Mode (|||${pasvSocket!!.localPort}|)")
            } catch (e: Exception) {
                send("425 Cannot open passive connection.")
            }
        }

        private fun openPort(arg: String) {
            try {
                val nums = arg.split(',').map { it.trim().toInt() }
                val ip = "${nums[0]}.${nums[1]}.${nums[2]}.${nums[3]}"
                val p = nums[4] * 256 + nums[5]
                portSocket = Socket(ip, p)
                send("200 PORT command successful.")
            } catch (e: Exception) {
                send("425 Cannot open data connection.")
            }
        }

        private fun acceptData(): Socket? {
            return try {
                val ps = pasvSocket
                if (ps != null) {
                    val s = ps.accept()
                    closeQuietly(ps)
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

        // ------------------------------------------------------------------
        // 目录列举 / 文件传输
        // ------------------------------------------------------------------
        /** 解析 LIST 参数中的目标目录（可能带 -l/-a 等选项，或无参数）。 */
        private fun targetDirOf(arg: String): DocumentFile {
            var a = arg.trim()
            if (a.startsWith("-")) {
                val sp = a.indexOf(' ')
                a = if (sp < 0) "" else a.substring(sp + 1).trim()
            }
            if (a.isEmpty()) return cwd
            return resolvePath(a)?.file ?: cwd
        }

        private fun listDir(arg: String, namesOnly: Boolean) {
            val ds = acceptData() ?: run {
                send("425 Use PASV or PORT first.")
                return
            }
            send("150 Opening data connection for directory list.")
            try {
                val w = BufferedWriter(OutputStreamWriter(ds.getOutputStream(), liveCharset))
                val dir = targetDirOf(arg)
                val fmt = SimpleDateFormat("MMM dd HH:mm", Locale.US)
                for (e in DocUtils.listEntries(ctx, config.root, dir, config.showHidden)) {
                    if (namesOnly) {
                        w.write(e.name)
                        w.write("\r\n")
                    } else {
                        val flag = if (e.isDirectory) "d" else "-"
                        val date = if (e.lastModified > 0) fmt.format(Date(e.lastModified)) else "Jan 01 00:00"
                        val size = if (e.isDirectory) 0L else e.size
                        w.write("$flag" + "rwxr-xr-x 1 owner group $size $date ${e.name}")
                        w.write("\r\n")
                    }
                }
                w.flush()
            } catch (e: Exception) {
            } finally {
                closeQuietly(ds)
            }
            send("226 Directory send OK.")
        }

        private fun mlsd(arg: String) {
            val ds = acceptData() ?: run {
                send("425 Use PASV or PORT first.")
                return
            }
            send("150 Opening data connection for directory list.")
            try {
                val w = BufferedWriter(OutputStreamWriter(ds.getOutputStream(), liveCharset))
                val dir = targetDirOf(arg)
                for (e in DocUtils.listEntries(ctx, config.root, dir, config.showHidden)) {
                    val sb = StringBuilder()
                    sb.append("type=").append(if (e.isDirectory) "dir" else "file")
                    if (!e.isDirectory) sb.append(";size=").append(e.size)
                    if (e.lastModified > 0) sb.append(";modify=").append(mlsTime(e.lastModified))
                    sb.append("; ").append(e.name)
                    w.write(sb.toString())
                    w.write("\r\n")
                }
                w.flush()
            } catch (e: Exception) {
            } finally {
                closeQuietly(ds)
            }
            send("226 Directory send OK.")
        }

        private fun mlst(arg: String) {
            val a = arg.trim()
            val r = if (a.isEmpty()) Resolved(cwd, cwdRel) else resolvePath(a)
            if (r == null) {
                send("550 File not found.")
                return
            }
            val f = r.file
            val name = if (r.rel == "/") "/" else r.rel.substringAfterLast('/')
            val sb = StringBuilder()
            sb.append("type=").append(if (f.isDirectory) "dir" else "file")
            if (!f.isDirectory) sb.append(";size=").append(f.length())
            val t = f.lastModified()
            if (t > 0) sb.append(";modify=").append(mlsTime(t))
            send("250-Listing $name")
            send(" $sb; $name")
            send("250 End")
        }

        private fun retrieve(arg: String) {
            val f = resolvePath(arg)?.file
            if (f == null || !f.isFile) {
                send("550 File not found.")
                return
            }
            val ds = acceptData() ?: run {
                send("425 Use PASV or PORT first.")
                return
            }
            send("150 Opening BINARY mode data connection.")
            var sent = 0L
            var err: String? = null
            try {
                val input = ctx.contentResolver.openInputStream(f.uri)
                if (input == null) {
                    err = "openInputStream returned null"
                } else {
                    input.use { ins ->
                        if (restOffset > 0) skipFully(ins, restOffset)
                        ds.getOutputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                sent += n
                            }
                            out.flush()
                        }
                    }
                }
            } catch (e: Exception) {
                err = e.toString()
            } finally {
                restOffset = 0
                closeQuietly(ds)
            }
            if (err == null) send("226 Transfer complete.")
            else send("451 Transfer aborted: $err")
        }

        private fun store(arg: String, append: Boolean) {
            val t = splitForCreate(arg) ?: run {
                send("550 Invalid file name.")
                return
            }
            val existing = findChild(t.parent, t.name)
            val target = existing ?: t.parent.createFile("application/octet-stream", t.name)
            if (target == null || target.isDirectory) {
                send("550 Cannot create file.")
                return
            }
            val ds = acceptData() ?: run {
                send("425 Use PASV or PORT first.")
                return
            }
            send("150 Ok to send data.")
            try {
                val mode = if (append) "wa" else "w"
                ds.getInputStream().use { input ->
                    ctx.contentResolver.openOutputStream(target.uri, mode)?.use { out ->
                        input.copyTo(out)
                    }
                }
            } catch (e: Exception) {
            } finally {
                restOffset = 0
                closeQuietly(ds)
            }
            send("226 Transfer complete.")
        }

        private fun skipFully(input: java.io.InputStream, n: Long) {
            var remaining = n
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped <= 0) {
                    if (input.read() < 0) break
                    remaining--
                } else {
                    remaining -= skipped
                }
            }
        }

        // ------------------------------------------------------------------
        // 行读取 / 解码 / 发送
        // ------------------------------------------------------------------
        /** 按字节读取一行（CRLF 结尾），不做任何编码转换。 */
        private fun readCommandLine(): ByteArray? {
            // 协议分流时已读出的首行（如 "USER anonymous"）优先返回
            prefetched?.let {
                prefetched = null
                return it
            }
            val buf = ByteArrayOutputStream(256)
            var prev = -1
            while (true) {
                val b = rawInput.read()
                if (b < 0) {
                    if (buf.size() == 0) return null
                    break
                }
                if (prev == '\r'.code && b == '\n'.code) break
                if (b != '\r'.code) buf.write(b)
                prev = b
            }
            return buf.toByteArray()
        }

        /**
         * 无损解码整行命令字节：严格 UTF-8 成功即认定 UTF-8，否则认定 GB18030。
         * 两者都不使用替换字符（不会产生 '?'），不丢字节；纯 ASCII 行不改变既有判定。
         */
        private fun decodeLossless(bytes: ByteArray): String {
            if (bytes.none { (it.toInt() and 0xFF) > 0x7F }) {
                return String(bytes, StandardCharsets.US_ASCII)
            }
            try {
                val s = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                liveCharset = StandardCharsets.UTF_8
                return s
            } catch (e: Exception) {
                val g = gb()
                liveCharset = g
                return String(bytes, g)
            }
        }

        private fun send(msg: String) {
            try {
                rawOutput.write((msg + "\r\n").toByteArray(liveCharset))
                rawOutput.flush()
            } catch (e: Exception) {
            }
        }

        /**
         * 临时诊断：把命令序列与关键解析结果追加到共享目录根下的 _ftp_debug.log。
         * 仅用于定位“同名文件下载失败”，问题确认后可整段删除。
         *
         * 文件句柄只解析一次并缓存：findFile 不可靠，若每次都查找会不断 createFile，
         * 生成 "_ftp_debug.log (1)…(32)" 一堆副本（上一轮日志就是这样丢的）。
         */

        private fun cleanup() {
            closeQuietly(pasvSocket)
            closeQuietly(portSocket)
            try {
                sock.close()
            } catch (e: Exception) {
            }
        }

        private fun closeQuietly(c: java.io.Closeable?) {
            try {
                c?.close()
            } catch (e: Exception) {
            }
        }
    }
}
