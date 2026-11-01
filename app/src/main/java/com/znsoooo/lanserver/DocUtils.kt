package com.znsoooo.lanserver

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

object DocUtils {

    /**
     * 目录子项的轻量描述。
     * 关键：size/name/isDirectory 在列举时一次性从 cursor 读取，
     * 不再对 DocumentFile 反复触发跨进程查询（见 listEntries 说明）。
     */
    data class Entry(
        val uri: Uri,
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long = 0L
    )

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

    /** 从根 DocumentFile 取出其对应的 tree Uri（形如 content://.../tree/<id>）。 */
    private fun treeUriOf(root: DocumentFile): Uri =
        DocumentsContract.buildTreeDocumentUri(
            root.uri.authority,
            DocumentsContract.getTreeDocumentId(root.uri)
        )

    /**
     * 单次 ContentResolver 查询取回目录下全部子项（名称/类型/大小）。
     *
     * 为什么需要它：DocumentFile 的 name / isDirectory / length 每一次调用都会触发一次
     * 跨进程 query（TreeDocumentFile 是惰性实现）。逐文件列举 1200 个文件 ≈ 3600 次 IPC，
     * 在 Android 上每次约数十毫秒，累计可达 130+ 秒。改为一次性批量查询后，无论目录多大
     * 都只有 1 次查询，文件大小来自文件系统元数据（COLUMN_SIZE），无需打开文件本身。
     */
    private fun queryChildren(
        ctx: Context,
        treeUri: Uri,
        parentDocId: String,
        showHidden: Boolean
    ): List<Entry> {
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val out = ArrayList<Entry>()
        try {
            ctx.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                val idIdx =
                    c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (c.moveToNext()) {
                    val docId = c.getString(idIdx)
                    val name = if (nameIdx >= 0) (c.getString(nameIdx) ?: "") else ""
                    val mime = if (mimeIdx >= 0) (c.getString(mimeIdx) ?: "") else ""
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val size =
                        if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                    val mod =
                        if (modIdx >= 0 && !c.isNull(modIdx)) c.getLong(modIdx) else 0L
                    if (!showHidden && isHidden(name)) continue
                    out.add(
                        Entry(
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                            name,
                            isDir,
                            size,
                            mod
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // 极少数 provider 不支持批量子项查询时，退化为逐文件列举以保证可用性
            try {
                val dir = DocumentFile.fromTreeUri(
                    ctx,
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
                )
                dir?.listFiles()?.forEach { f ->
                    if (!showHidden && isHidden(f.name ?: "")) return@forEach
                    out.add(Entry(f.uri, f.name ?: "", f.isDirectory, f.length(), f.lastModified()))
                }
            } catch (_: Exception) {
            }
        }
        out.sortWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        return out
    }

    /**
     * 列举目录子项（文件夹在前、同级按名称不区分大小写排序）。
     * 通过一次批量查询完成，避免 DocumentFile 逐文件查询带来的性能问题。
     */
    fun listEntries(
        ctx: Context,
        root: DocumentFile,
        dir: DocumentFile,
        showHidden: Boolean
    ): List<Entry> {
        return queryChildren(
            ctx,
            treeUriOf(root),
            DocumentsContract.getDocumentId(dir.uri),
            showHidden
        )
    }

    /**
     * 统计目录内子项数量（文件 + 子文件夹），用于 HTTP 列表展示“XX 项”。
     * 与“显示隐藏”开关同步；同样通过一次批量查询完成。
     */
    fun countChildren(
        ctx: Context,
        root: DocumentFile,
        dirUri: Uri,
        showHidden: Boolean
    ): Int {
        return queryChildren(
            ctx,
            treeUriOf(root),
            DocumentsContract.getDocumentId(dirUri),
            showHidden
        ).size
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
