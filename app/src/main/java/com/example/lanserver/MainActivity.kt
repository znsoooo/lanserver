package com.example.lanserver

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.ImageView
import android.widget.Switch
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile

class MainActivity : Activity() {

    private lateinit var tvIp: TextView
    private lateinit var chipHttp: Button
    private lateinit var chipFtp: Button
    private lateinit var etPort: EditText
    private lateinit var tvDir: TextView
    private lateinit var btnStart: TextView
    private lateinit var tvUrl: TextView
    private lateinit var ivQr: ImageView
    private lateinit var tvQrHint: TextView
    private lateinit var swHidden: Switch
    private lateinit var swWrite: Switch
    private lateinit var vDot: View
    private lateinit var tvStatus: TextView

    private var pickedTreeUri: Uri? = null
    private var server: Thread? = null
    private var running = false
    private var selectedType = "HTTP"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvIp = findViewById(R.id.tvIp)
        chipHttp = findViewById(R.id.chipHttp)
        chipFtp = findViewById(R.id.chipFtp)
        etPort = findViewById(R.id.etPort)
        tvDir = findViewById(R.id.tvDir)
        btnStart = findViewById(R.id.btnStart)
        tvUrl = findViewById(R.id.tvUrl)
        ivQr = findViewById(R.id.ivQr)
        tvQrHint = findViewById(R.id.tvQrHint)
        swHidden = findViewById(R.id.swHidden)
        swWrite = findViewById(R.id.swWrite)
        vDot = findViewById(R.id.vDot)
        tvStatus = findViewById(R.id.tvStatus)

        refreshIp()
        findViewById<Button>(R.id.btnPickDir).setOnClickListener { pickDir() }
        btnStart.setOnClickListener { toggleServer() }

        // 分段按钮：选择服务器类型
        chipHttp.setOnClickListener { selectType("HTTP") }
        chipFtp.setOnClickListener { selectType("FTP") }
        selectType("HTTP")

        // 复制按钮：访问网址 / 共享目录路径
        findViewById<Button>(R.id.btnCopyUrl).setOnClickListener {
            val text = tvUrl.text.toString()
            if (text.isNotEmpty()) copyText(text, "访问地址")
        }
        findViewById<Button>(R.id.btnCopyDir).setOnClickListener {
            val uri = pickedTreeUri
            if (uri == null) {
                Toast.makeText(this, "请先选择目录", Toast.LENGTH_SHORT).show()
            } else {
                copyText(DocUtils.friendlyPath(uri), "目录路径")
            }
        }

        // 端口变化时实时刷新访问网址与二维码
        etPort.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {
                updateUrl()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // 访问网址与二维码始终显示（即使尚未启动服务）
        updateUrl()
        setRunningUi(false)
    }

    private fun selectType(type: String) {
        selectedType = type
        val http = type == "HTTP"
        chipHttp.isSelected = http
        chipFtp.isSelected = !http
        chipHttp.setTextColor(if (http) Color.WHITE else Color.parseColor("#5b6b7d"))
        chipFtp.setTextColor(if (!http) Color.WHITE else Color.parseColor("#5b6b7d"))
        updateUrl()
    }

    private fun refreshIp() {
        tvIp.text = IpUtils.getIp()
        updateUrl()
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
        val type = selectedType
        val showHidden = swHidden.isChecked
        val allowWrite = swWrite.isChecked
        server = if (type == "FTP") {
            SimpleFtpServer(this, root, port, showHidden, allowWrite)
        } else {
            SimpleHttpServer(this, root, port, showHidden, allowWrite)
        }
        server!!.start()
        running = true
        updateUrl()
        setRunningUi(true)
        Toast.makeText(this, "$type 服务已启动（端口 $port）", Toast.LENGTH_SHORT).show()
    }

    private fun stopServer() {
        (server as? SimpleHttpServer)?.stopServer()
        (server as? SimpleFtpServer)?.stopServer()
        server = null
        running = false
        setRunningUi(false)
    }

    /** 同步运行状态到 UI：标题胶囊 + 大按钮样式（启动/停止）。 */
    private fun setRunningUi(isRunning: Boolean) {
        vDot.isSelected = isRunning
        tvStatus.text = if (isRunning) "运行中" else "未启动"
        btnStart.isSelected = isRunning
        btnStart.text = if (isRunning) "停止服务" else "启动服务"
        btnStart.setTextColor(if (isRunning) Color.WHITE else Color.parseColor("#5b6b7d"))
    }

    private fun updateUrl() {
        val ip = IpUtils.getIp()
        val port = getPort()
        val scheme = if (selectedType == "FTP") "ftp" else "http"
        val url = "$scheme://$ip:$port"
        tvUrl.text = url
        tvQrHint.text = url
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
