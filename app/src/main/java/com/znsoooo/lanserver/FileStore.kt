package com.znsoooo.lanserver

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 基于 java.io.File 的文件访问层（需要「所有文件访问」权限）。
 *
 * 相比原先的 SAF/DocumentFile：
 *  - 直接得到真实路径（可访问 /storage/emulated/0 根目录及任意子目录）；
 *  - 列目录、重命名、移动都是文件系统原生操作，无需跨进程查询；
 *  - 断点续传（REST）能用 RandomAccessFile 正常 seek。
 *
 * 额外提供**虚拟收藏目录**：
 *  只要共享目录内至少有一个被系统标记为收藏的文件，共享目录（即 FTP/HTTP 的根）下
 *  就会多出一个 `@Favorite`，把这些文件按**相对共享目录的路径结构**映射进来，例如
 *      <共享目录>/DCIM/work/IMG_001.JPG  ->  /@Favorite/DCIM/work/IMG_001.JPG
 *  收藏集合只取当前共享目录内的文件，共享目录换成别的子目录时，@Favorite 里剩下的
 *  就是那个子目录范围内的收藏；子目录里没有收藏则整个虚拟目录都不出现。
 *  名称以 `@` 开头：既能在按名称排序时排到最前面，也能避开普通目录重名。
 *  （若磁盘上真有同名目录，虚拟视图优先。）
 *  @Favorite 只出现在根这一层，不会在各级子目录里重复出现。
 *  它只是视图（文件实体仍在原路径），因此不影响原目录，也不会复制数据。
 *  在 @Favorite 内一律视为只读。
 */
object FileStore {

    const val FAVORITE = "@Favorite"

    /** 应用上下文：让 resolve/child 这类没有 Context 参数的入口也能按需刷新收藏。 */
    @Volatile
    private var appCtx: Context? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    data class Entry(
        val file: File,
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val lastModified: Long,
        /** true 表示这是虚拟目录（磁盘上不存在对应路径） */
        val virtualDir: Boolean = false
    )

    /** 收藏文件的相对路径（相对当前共享目录 root），如 "DCIM/work/IMG_001.JPG"。 */
    @Volatile
    private var favoriteRels: Set<String> = emptySet()

    /** 收藏集合是否可用于当前根；共享目录不可读时置 false。 */
    @Volatile
    private var favoriteEnabled = false

    /** 当前收藏集合是按哪个共享目录算出来的，换目录必须重算。 */
    @Volatile
    private var favoriteRootPath = ""

    @Volatile
    private var favoriteLoadedAt = 0L

    // ------------------------------------------------------------------
    // 权限 / 根路径
    // ------------------------------------------------------------------
    fun primaryRoot(): File = Environment.getExternalStorageDirectory()

    fun hasAllFilesAccess(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

    // ------------------------------------------------------------------
    // 收藏（MediaStore.IS_FAVORITE，API 30+；由系统/相册写入）
    // ------------------------------------------------------------------
    fun hasFavorites(): Boolean = favoriteEnabled && favoriteRels.isNotEmpty()

    fun favoriteCount(): Int = if (favoriteEnabled) favoriteRels.size else 0

    /**
     * 刷新收藏列表（默认 60 秒缓存，避免每次列目录都查 MediaStore）。
     *
     * 共享目录可以是任意子目录，收藏集合只保留该目录范围内的文件；
     * 因此换了共享目录就必须重算（路径变了会跳过缓存）。
     */
    fun refreshFavorites(ctx: Context, root: File, force: Boolean = false) {
        val rp = root.absolutePath.trimEnd('/')
        if (rp != favoriteRootPath) {
            favoriteRootPath = rp
            favoriteLoadedAt = 0L
        }
        favoriteEnabled = true
        val now = System.currentTimeMillis()
        if (!force && now - favoriteLoadedAt < 60_000L) return
        favoriteLoadedAt = now
        favoriteRels = queryFavorites(ctx, rp)
    }

    /** 没有 Context 的调用点（resolve/child）用它保证虚拟层已就绪。 */
    private fun ensureFavorites(root: File) {
        val c = appCtx ?: return
        refreshFavorites(c, root)
    }

    private fun queryFavorites(ctx: Context, rootPath: String): Set<String> {
        val out = HashSet<String>()
        try {
            val collection = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(MediaStore.MediaColumns.DATA)
            // IS_FAVORITE 是 API 30 新增列，直接用字面量以便在低版本安全失败
            val selection = "is_favorite = 1"
            ctx.contentResolver.query(collection, projection, selection, null, null)?.use { c ->
                val idx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (idx < 0) return out
                while (c.moveToNext()) {
                    val path = c.getString(idx) ?: continue
                    // 只取落在当前共享目录内的收藏，相对路径按共享目录计算
                    if (!path.startsWith("$rootPath/")) continue
                    val rel = path.substring(rootPath.length + 1)
                    if (rel.isNotEmpty()) out.add(rel)
                }
            }
        } catch (e: Exception) {
            // 低版本没有该列 / 系统未实现：按“无收藏”处理
        }
        return out
    }

    // ------------------------------------------------------------------
    // 虚拟路径判断
    // ------------------------------------------------------------------
    private fun favRootAbs(root: File): String = File(root, FAVORITE).absolutePath

    /** 该路径是否落在虚拟 Favorite 内。 */
    fun isVirtualPath(root: File, f: File): Boolean {
        // 没有收藏时不存在虚拟层：此时磁盘上同名目录按普通目录对待
        if (!favoriteEnabled || favoriteRels.isEmpty()) return false
        val abs = f.absolutePath
        val fr = favRootAbs(root)
        return abs == fr || abs.startsWith("$fr/")
    }

    /** dir 是否就是共享目录本身（@Favorite 只在这一层出现）。 */
    private fun isRootDir(root: File, dir: File): Boolean =
        dir.absolutePath.trimEnd('/') == root.absolutePath.trimEnd('/')

    /** 该路径是否在 @Favorite 内（含自身），用于禁止写入。 */
    fun isInsideFavorite(root: File, f: File): Boolean = isVirtualPath(root, f)

    /**
     * 虚拟路径 → 磁盘上的真实文件；非虚拟路径原样返回。
     * 下载/读取大小与时间都必须走这里，否则会去读 @Favorite/... 这个并不存在的路径。
     */
    fun realFile(root: File, f: File): File {
        if (!isVirtualPath(root, f)) return f
        val sub = favSub(root, f)
        return if (sub.isEmpty()) root else File(root, sub)
    }

    /** 虚拟目录在收藏集合中对应的子路径（相对 Favorite）。 */
    private fun favSub(root: File, f: File): String {
        val fr = favRootAbs(root)
        val abs = f.absolutePath
        return if (abs == fr) "" else abs.substring(fr.length + 1)
    }

    private fun favoriteHas(sub: String): Boolean {
        if (sub.isEmpty()) return favoriteRels.isNotEmpty()
        return favoriteRels.any { it == sub || it.startsWith("$sub/") }
    }

    // ------------------------------------------------------------------
    // 核心操作
    // ------------------------------------------------------------------
    fun isDirectory(root: File, f: File): Boolean {
        if (isVirtualPath(root, f)) {
            val sub = favSub(root, f)
            // 注意：不能只看“存在”。收藏里的文件本身也是一条 rel，
            // 必须要求存在更深的 "sub/..." 才算目录，否则文件会被当成目录打开。
            if (sub.isEmpty()) return favoriteRels.isNotEmpty()
            return favoriteRels.any { it.startsWith("$sub/") }
        }
        return f.isDirectory
    }

    fun exists(root: File, f: File): Boolean {
        if (isVirtualPath(root, f)) return favoriteHas(favSub(root, f))
        return f.exists()
    }

    /** 查找子项（含虚拟 @Favorite 的下一层）。 */
    fun child(root: File, dir: File, name: String): File? {
        if (favoriteEnabled && favoriteRels.isNotEmpty()) {
            if (isVirtualPath(root, dir) || dir.absolutePath.trimEnd('/') == favRootAbs(root)) {
                val sub = favSub(root, dir)
                val target = if (sub.isEmpty()) name else "$sub/$name"
                return if (favoriteHas(target)) File(dir, name) else null
            }
            // 根目录下的 @Favorite 在磁盘上并不存在，必须在这里“造”出来，
            // 否则 resolve() 会判为不存在（HTTP 便会重定向回根目录、FTP 则 CWD 失败）。
            if (name == FAVORITE && isRootDir(root, dir)) {
                return File(root, FAVORITE)
            }
        }
        val f = File(dir, name)
        return if (f.exists()) f else null
    }

    /** 解析相对路径（逐段，支持 .. 与虚拟 @Favorite）。 */
    fun resolve(root: File, rel: String): File? {
        ensureFavorites(root)
        var cur = root
        for (seg in rel.split('/')) {
            when {
                seg.isEmpty() || seg == "." -> {}
                seg == ".." -> {
                    cur = if (isVirtualPath(root, cur)) {
                        val sub = favSub(root, cur)
                        if (sub.isEmpty()) root else File(favRootAbs(root), sub.substringBeforeLast('/', ""))
                    } else {
                        cur.parentFile ?: root
                    }
                }
                else -> {
                    cur = child(root, cur, seg) ?: return null
                }
            }
        }
        return cur
    }

    /** 列目录（文件夹在前，按名称不区分大小写）。 */
    fun list(ctx: Context, root: File, dir: File, showHidden: Boolean): List<Entry> {
        // 先刷新（内部有 60 秒缓存）：确保首次访问 /Favorite 时虚拟层已就绪
        refreshFavorites(ctx, root)
        if (favoriteEnabled && favoriteRels.isNotEmpty() &&
            (isVirtualPath(root, dir) || dir.absolutePath.trimEnd('/') == favRootAbs(root))
        ) {
            return listFavorite(root, favSub(root, dir))
        }
        val children = dir.listFiles() ?: return emptyList()
        val out = ArrayList<Entry>()
        for (f in children) {
            val name = f.name ?: continue
            if (!showHidden && name.startsWith(".")) continue
            val isDir = f.isDirectory
            out.add(Entry(f, name, isDir, if (isDir) 0L else f.length(), f.lastModified()))
        }
        // 根目录追加虚拟 @Favorite（有收藏才出现；子目录不追加）
        if (favoriteEnabled && favoriteRels.isNotEmpty() && isRootDir(root, dir)) {
            out.add(Entry(File(root, FAVORITE), FAVORITE, true, 0L, 0L, virtualDir = true))
        }
        // @Favorite 固定置顶（其余仍按 目录在前、名称不区分大小写）
        out.sortWith(compareBy({ it.name != FAVORITE }, { !it.isDir }, { it.name.lowercase() }))
        return out
    }

    /** 虚拟 @Favorite 下某一层的子项。 */
    private fun listFavorite(root: File, sub: String): List<Entry> {
        val prefix = if (sub.isEmpty()) "" else "$sub/"
        val map = LinkedHashMap<String, Boolean>()   // name -> isDir
        for (rel in favoriteRels) {
            if (!rel.startsWith(prefix)) continue
            val rest = rel.substring(prefix.length)
            if (rest.isEmpty()) continue
            val i = rest.indexOf('/')
            val name = if (i < 0) rest else rest.substring(0, i)
            val isDir = i >= 0
            map[name] = (map[name] ?: false) || isDir
        }
        val out = ArrayList<Entry>()
        for ((name, isDir) in map) {
            if (isDir) {
                val virtPath = if (sub.isEmpty()) "$FAVORITE/$name" else "$FAVORITE/$sub/$name"
                out.add(Entry(File(root, virtPath), name, true, 0L, 0L, virtualDir = true))
            } else {
                val realRel = prefix + name
                val f = File(root, realRel)
                out.add(Entry(f, name, false, f.length(), f.lastModified()))
            }
        }
        out.sortWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        return out
    }

    fun count(ctx: Context, root: File, dir: File, showHidden: Boolean): Int =
        list(ctx, root, dir, showHidden).size

    /** 根据扩展名判断 MIME（HTTP 响应 / FTP MLSD 用）。 */
    fun mimeOf(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html; charset=utf-8"
            lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".md") -> "text/plain; charset=utf-8"
            lower.endsWith(".css") -> "text/css"
            lower.endsWith(".js") -> "application/javascript"
            lower.endsWith(".json") -> "application/json"
            lower.endsWith(".xml") -> "application/xml"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".svg") -> "image/svg+xml"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".m4a") -> "audio/mp4"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".zip") || lower.endsWith(".git") || lower.endsWith(".bundle") -> "application/zip"
            lower.endsWith(".apk") -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }
}
