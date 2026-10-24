package com.example.lanserver

import android.net.Uri
import androidx.documentfile.provider.DocumentFile

object DocUtils {

    /** 根据相对路径在 DocumentFile 树中解析出目标文件/目录。 */
    fun resolve(base: DocumentFile, path: String): DocumentFile? {
        val trimmed = path.trimStart('/')
        if (trimmed.isEmpty()) return base
        var cur = base
        for (seg in trimmed.split('/')) {
            if (seg.isEmpty() || seg == ".") continue
            if (seg == "..") {
                cur = cur.parentFile ?: base
                continue
            }
            val next = cur.findFile(seg) ?: return null
            cur = next
        }
        return cur
    }

    /** 是否为隐藏文件（名称以 “.” 开头）。 */
    fun isHidden(name: String): Boolean = name.startsWith('.')

    /**
     * 列出目录子项，排序规则：
     * 1) 文件夹排在文件前面；
     * 2) 同级按名称字母序（不区分大小写）排列。
     * showHidden 为 false 时过滤掉隐藏项（名称以 “.” 开头）。
     */
    fun listChildren(dir: DocumentFile, showHidden: Boolean): List<DocumentFile> {
        val files = dir.listFiles()?.toList() ?: emptyList()
        return files
            .filter { showHidden || !isHidden(it.name ?: "") }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name?.lowercase() ?: "" }))
    }

    /**
     * 将 SAF 的 content:// URI 转换为可读的存储路径，例如：
     *   /storage/emulated/0/Download/分享保存
     * “primary:” 代表设备主存储（/storage/emulated/0）；
     * 其它卷（如 SD 卡）形如 /storage/<卷名>/...。
     */
    fun friendlyPath(uri: Uri): String {
        // Uri.toString() 会把文档 ID 中的 “:” 编码为 “%3A”，先解码还原
        val s = java.net.URLDecoder.decode(uri.toString(), "UTF-8")
        // 主存储卷 primary:（取最后一个，规避个别机型 URI 重复段）
        val idx = s.lastIndexOf("primary:")
        if (idx >= 0) {
            val rest = s.substring(idx + "primary:".length)
            return "/storage/emulated/0/$rest"
        }
        // 其它卷： tree/卷名:路径 或 document/卷名:路径
        val m = Regex("(?:tree|document)/([^:/]+):(.+)$").find(s)
        if (m != null) {
            return "/storage/${m.groupValues[1]}/${m.groupValues[2]}"
        }
        return s
    }

    /** 根据文件名猜测 MIME 类型（HTTP 响应用）。 */
    fun mimeOf(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html; charset=utf-8"
            lower.endsWith(".txt") -> "text/plain; charset=utf-8"
            lower.endsWith(".css") -> "text/css"
            lower.endsWith(".js") -> "application/javascript"
            lower.endsWith(".json") -> "application/json"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".zip") -> "application/zip"
            lower.endsWith(".apk") -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }
}
