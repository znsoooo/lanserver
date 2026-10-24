package com.example.lanserver

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile

class MainActivity : Activity() {

    private lateinit var tvIp: TextView
    private lateinit var spinnerType: Spinner
    private lateinit var etPort: EditText
    private lateinit var tvDir: TextView
    private lateinit var btnStart: Button
    private lateinit var tvUrl: TextView
    private lateinit var ivQr: ImageView
    private lateinit var swHidden: Switch

    private var pickedTreeUri: Uri? = null
    private var server: Thread? = null
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvIp = findViewById(R.id.tvIp)
        spinnerType = findViewById(R.id.spinnerType)
        etPort = findViewById(R.id.etPort)
        tvDir = findViewById(R.id.tvDir)
        btnStart = findViewById(R.id.btnStart)
        tvUrl = findViewById(R.id.tvUrl)
        ivQr = findViewById(R.id.ivQr)
        swHidden = findViewById(R.id.swHidden)

        spinnerType.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            arrayOf("FTP", "HTTP")
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        refreshIp()
        findViewById<Button>(R.id.btnRefreshIp).setOnClickListener { refreshIp() }
        findViewById<Button>(R.id.btnPickDir).setOnClickListener { pickDir() }
        btnStart.setOnClickListener { toggleServer() }

        // 长按复制：目录路径
        tvDir.setOnLongClickListener {
            val uri = pickedTreeUri
            if (uri == null) {
                Toast.makeText(this, "请先选择目录", Toast.LENGTH_SHORT).show()
            } else {
                copyText(DocUtils.friendlyPath(uri), "目录路径")
            }
            true
        }
        // 长按复制：访问地址（去掉旁边的复制按钮）
        tvUrl.setOnLongClickListener {
            val text = tvUrl.text.toString()
            if (text.isNotEmpty()) copyText(text, "访问地址")
            true
        }
    }

    private fun refreshIp() {
        tvIp.text = IpUtils.getIp()
        if (running) updateUrl()
    }

    private fun pickDir() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        startActivityForResult(intent, REQ_PICK_DIR)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_DIR && resultCode == Activity.RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // 部分机型不支持持久化，忽略
                }
                pickedTreeUri = uri
                // 显示可读的真实存储路径，而非 SAF 的 content URI
                tvDir.text = DocUtils.friendlyPath(uri)
            }
        }
    }

    private fun getPort(): Int {
        val p = etPort.text.toString().toIntOrNull()
        return if (p != null && p in 1..65535) p else 8888
    }

    private fun toggleServer() {
        if (running) stopServer() else startServer()
    }

    private fun startServer() {
        if (pickedTreeUri == null) {
            Toast.makeText(this, "请先选择共享目录", Toast.LENGTH_SHORT).show()
            return
        }
        val root = DocumentFile.fromTreeUri(this, pickedTreeUri!!)
        if (root == null || !root.exists()) {
            Toast.makeText(this, "目录无效", Toast.LENGTH_SHORT).show()
            return
        }
        val port = getPort()
        val type = spinnerType.selectedItem.toString()
        val showHidden = swHidden.isChecked
        server = if (type == "FTP") {
            SimpleFtpServer(this, root, port, showHidden)
        } else {
            SimpleHttpServer(this, root, port, showHidden)
        }
        server!!.start()
        running = true
        updateUrl()
        btnStart.text = "停止服务"
        Toast.makeText(this, "$type 服务已启动（端口 $port）", Toast.LENGTH_SHORT).show()
    }

    private fun stopServer() {
        (server as? SimpleHttpServer)?.stopServer()
        (server as? SimpleFtpServer)?.stopServer()
        server = null
        running = false
        btnStart.text = "启动服务"
        tvUrl.text = ""
        ivQr.visibility = View.GONE
    }

    private fun updateUrl() {
        val ip = IpUtils.getIp()
        val port = getPort()
        val type = spinnerType.selectedItem.toString()
        val scheme = if (type == "FTP") "ftp" else "http"
        val url = "$scheme://$ip:$port/"
        tvUrl.text = url
        val bmp = QrUtils.gen(url, 440)
        ivQr.setImageBitmap(bmp)
        ivQr.visibility = View.VISIBLE
    }

    private fun copyText(text: String, label: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "已复制：$text", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        if (running) stopServer()
        super.onDestroy()
    }

    companion object {
        private const val REQ_PICK_DIR = 1
    }
}
