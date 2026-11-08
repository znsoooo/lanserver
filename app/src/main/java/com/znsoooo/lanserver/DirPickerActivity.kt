package com.znsoooo.lanserver

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

/**
 * 自建的文件夹选择器。
 *
 * 为什么不用 SAF：Android 11 起 `ACTION_OPEN_DOCUMENT_TREE` 不允许把存储根作为结果，
 * 部分 ROM 连 Download 之类的目录也会拦。应用已经拿到「所有文件访问」，
 * 可以直接遍历文件系统，因此这里自己实现一个：进入目录就能看到里面的文件与子目录
 * （文件只用于辅助判断，不能选中），当前所在目录就是将被选中的共享目录。
 */
class DirPickerActivity : Activity() {

    companion object {
        const val EXTRA_START = "start_path"
        const val EXTRA_PATH = "picked_path"
    }

    private lateinit var tvPath: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var list: ListView
    private lateinit var btnUp: TextView
    private lateinit var adapter: EntryAdapter

    private val primary: File = FileStore.primaryRoot()
    private var cur: File = primary
    private val items = ArrayList<Entry>()

    private class Entry(
        val file: File,
        val name: String,
        val isDir: Boolean,
        val size: Long,
        /** 文件夹内的直接子项数（与列表一致，不计隐藏项） */
        val childCount: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dir_picker)

        tvPath = findViewById(R.id.tvPath)
        tvEmpty = findViewById(R.id.tvEmpty)
        list = findViewById(R.id.list)
        btnUp = findViewById(R.id.btnUp)

        adapter = EntryAdapter()
        list.adapter = adapter
        list.emptyView = tvEmpty

        val restored = savedInstanceState?.getString("cur_path")?.let { File(it) }
        val start = restored ?: intent.getStringExtra(EXTRA_START)?.let { File(it) }
        load(if (start != null && isInsidePrimary(start)) start else primary)

        list.setOnItemClickListener { _, _, pos, _ ->
            val e = items[pos]
            if (e.isDir) {
                load(e.file)
            } else {
                Toast.makeText(this, "这是文件，不是文件夹", Toast.LENGTH_SHORT).show()
            }
        }

        btnUp.setOnClickListener { goUp() }
        findViewById<TextView>(R.id.btnPick).setOnClickListener {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_PATH, cur.absolutePath))
            finish()
        }
    }

    /** 系统返回键直接退出选择器（取消）；回上一级只用路径行右侧的「上级」。 */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("cur_path", cur.absolutePath)
    }

    private fun goUp() {
        val p = cur.parentFile ?: return
        if (!isInsidePrimary(p)) return
        load(p)
    }

    private fun atPrimaryRoot(): Boolean =
        cur.absolutePath.trimEnd('/') == primary.absolutePath.trimEnd('/')

    /** 只在主存储内浏览，避免选到系统其它不可用分区。 */
    private fun isInsidePrimary(f: File): Boolean {
        val p = f.absolutePath.trimEnd('/')
        val r = primary.absolutePath.trimEnd('/')
        return p == r || p.startsWith("$r/")
    }

    private fun load(dir: File) {
        val children = try {
            dir.listFiles()
        } catch (e: Exception) {
            null
        }
        if (children == null) {
            Toast.makeText(this, "无法读取该目录（系统限制）", Toast.LENGTH_SHORT).show()
            return
        }
        cur = dir
        items.clear()
        for (f in children) {
            val name = f.name ?: continue
            // 隐藏项不显示（不提供开关）：.thumbnails 这类系统噪音没有选择价值
            if (name.startsWith(".")) continue
            val isDir = f.isDirectory
            val count = if (isDir) (f.list()?.count { !it.startsWith(".") } ?: 0) else 0
            items.add(Entry(f, name, isDir, if (isDir) 0L else f.length(), count))
        }
        items.sortWith(compareBy({ !it.isDir }, { it.name.lowercase(Locale.getDefault()) }))

        tvPath.text = cur.absolutePath
        btnUp.isEnabled = !atPrimaryRoot()
        btnUp.alpha = if (atPrimaryRoot()) 0.3f else 1f
        adapter.notifyDataSetChanged()
        list.setSelection(0)
    }

    private inner class EntryAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(this@DirPickerActivity)
                .inflate(R.layout.item_dir_picker, parent, false)
            val e = items[position]
            v.findViewById<ImageView>(R.id.ivIcon)
                .setImageResource(if (e.isDir) R.drawable.ic_folder else R.drawable.ic_file)
            v.findViewById<TextView>(R.id.tvName).apply {
                text = e.name
                setTextColor(Color.parseColor(if (e.isDir) "#1b2430" else "#5b6b7d"))
            }
            v.findViewById<TextView>(R.id.tvInfo).text =
                if (e.isDir) "${e.childCount} 项" else formatSize(e.size)
            return v
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024
        return String.format(Locale.US, "%.2f GB", gb)
    }
}
