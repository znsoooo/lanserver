package com.znsoooo.lanserver

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
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
    private lateinit var tvTitle: TextView

    private var pickedTreeUri: Uri? = null
    private var selectedType = "HTTP"

    /**
     * 初始化期间禁用状态保存。
     * 原因：onCreate 中回填控件（如 etPort.setText）会触发 TextWatcher/开关监听，
     * 进而调用 saveState()，而此时 selectedType 尚未由记忆值恢复（仍是默认 "HTTP"），
     * 会把记忆里的 FTP 覆盖成 HTTP（表现为“重装/重建后类型总是回到 HTTP，其它设置正常”）。
     */
    private var initializing = true

    // 默认共享目录：内部存储根 /storage/emulated/0
    private val defaultTreeUri: Uri =
        DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:")
    private val defaultDirText = "/storage/emulated/0"

    private val prefsName = "lanserver_prefs"

    // 服务需在 Activity 销毁（旋转、切后台）后继续运行，故用伴生对象持有线程引用，
    // 跨配置变更复用同一实例，避免重复占用端口。
    companion object {
        private const val REQ_PICK_DIR = 1
        @Volatile private var server: Thread? = null
        @Volatile private var running = false
        private var sType = "HTTP"

        /**
         * FTP 运行期配置（可热更新）。FTP 模式启动时创建，供界面改动“共享目录/读写/显示隐藏”
         * 时**就地上报**到正在运行的服务器；HTTP 模式下为 null。
         */
        @Volatile private var ftpConfig: FtpConfig? = null
    }

    // 网络变化监听：IP 改变时自动刷新地址与二维码
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { refreshIp() }
        }

        override fun onLost(network: Network) {
            runOnUiThread { refreshIp() }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            runOnUiThread { refreshIp() }
        }
    }

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
        tvTitle = findViewById(R.id.tvTitle)
        tvTitle.text = "局域网服务"

        // 载入上次使用状态（记忆）
        val sp = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val savedType = sp.getString("type", "HTTP") ?: "HTTP"
        val savedPort = sp.getString("port", "8888") ?: "8888"
        val savedWrite = sp.getBoolean("write", false)
        val savedHidden = sp.getBoolean("hidden", false)
        val savedUri = sp.getString("dir_uri", "") ?: ""
        pickedTreeUri = if (savedUri.isNotEmpty()) Uri.parse(savedUri) else defaultTreeUri
        tvDir.text = if (pickedTreeUri == defaultTreeUri) defaultDirText
        else DocUtils.friendlyPath(pickedTreeUri!!)

        refreshIp()
        findViewById<TextView>(R.id.btnPickDir).setOnClickListener { pickDir() }
        btnStart.setOnClickListener { toggleServer() }

        // 分段按钮：选择服务器类型（变化即生效）
        chipHttp.setOnClickListener { selectType("HTTP"); saveState() }
        chipFtp.setOnClickListener { selectType("FTP"); saveState() }

        // 端口变化：实时刷新地址/二维码，运行中则重启服务套用新端口
        etPort.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {
                updateUrl()
                restartIfRunning(silent = true)
                saveState()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // 写入 / 隐藏开关：FTP 运行时就地热更新，其它情况重启服务
        swWrite.setOnCheckedChangeListener { _, _ ->
            applyConfigChange()
            saveState()
        }
        swHidden.setOnCheckedChangeListener { _, _ ->
            applyConfigChange()
            saveState()
        }

        // 复制按钮：访问网址 / 共享目录路径
        findViewById<Button>(R.id.btnCopyUrl).setOnClickListener {
            val text = tvUrl.text.toString()
            if (text.isNotEmpty()) copyText(text, "访问地址")
        }
        findViewById<Button>(R.id.btnCopyDir).setOnClickListener {
            copyText(tvDir.text.toString(), "目录路径")
        }

        // 应用载入的状态
        etPort.setText(savedPort)
        swWrite.isChecked = savedWrite
        swHidden.isChecked = savedHidden

        // 若服务仍在后台运行（跨配置变更复用），恢复 UI 状态；否则按记忆初始化
        if (running && server?.isAlive == true) {
            applyTypeUI(sType)
            setRunningUi(true)
        } else {
            applyTypeUI(savedType)
            setRunningUi(false)
        }

        // 访问网址与二维码始终显示（即使尚未启动服务）
        updateUrl()

        // 注册网络变化监听（API 24+）
        if (Build.VERSION.SDK_INT >= 24) {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            try {
                cm.registerDefaultNetworkCallback(networkCallback)
            } catch (e: Exception) {
                // 个别机型不支持，忽略
            }
        }

        // 应用打开后默认启动服务；若启动过程抛异常（如端口占用），则保持关闭状态
        if (!running) {
            try {
                startServer(showToast = false)
            } catch (e: Exception) {
                setRunningUi(false)
                Toast.makeText(this, "服务启动失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // 初始化完成：此后控件的自动回调才允许写入记忆
        initializing = false
    }

    /** 仅更新分段按钮的选中样式与访问地址，不触发服务重启。 */
    private fun applyTypeUI(type: String) {
        selectedType = type
        val http = type == "HTTP"
        chipHttp.isSelected = http
        chipFtp.isSelected = !http
        chipHttp.setTextColor(if (http) Color.WHITE else Color.parseColor("#5b6b7d"))
        chipFtp.setTextColor(if (!http) Color.WHITE else Color.parseColor("#5b6b7d"))
        updateUrl()
    }

    private fun selectType(type: String) {
        applyTypeUI(type)
        // 类型切换会切换协议，运行中需重启服务
        restartIfRunning(silent = true)
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
                tvDir.text = DocUtils.friendlyPath(uri)
                // 共享目录变化：FTP 运行中就地热更新，其它情况重启服务
                applyConfigChange()
                saveState()
            }
        }
    }

    private fun getPort(): Int {
        val p = etPort.text.toString().toIntOrNull()
        return if (p != null && p in 1..65535) p else 8888
    }

    private fun toggleServer() {
        if (running) stopServer() else startServer(showToast = true)
    }

    private fun startServer(showToast: Boolean) {
        val root = DocumentFile.fromTreeUri(this, pickedTreeUri!!)
        if (root == null || !root.exists()) {
            Toast.makeText(this, "共享目录不可用，请重新选择", Toast.LENGTH_SHORT).show()
            return
        }
        val port = getPort()
        val type = selectedType
        val showHidden = swHidden.isChecked
        val allowWrite = swWrite.isChecked
        // 使用 ApplicationContext，使服务在 Activity 销毁后仍能访问 ContentResolver
        val appCtx = applicationContext
        ftpConfig = null
        server = if (type == "FTP") {
            // FTP 用可变配置对象承载 root/隐藏/读写，便于运行期热更新
            val cfg = FtpConfig(root, showHidden, allowWrite)
            ftpConfig = cfg
            SimpleFtpServer(appCtx, port, cfg)
        } else {
            SimpleHttpServer(appCtx, root, port, showHidden, allowWrite)
        }
        sType = type
        server!!.start()
        running = true
        updateUrl()
        setRunningUi(true)
        if (showToast) {
            Toast.makeText(this, "$type 服务已启动（端口 $port）", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopServer() {
        (server as? SimpleHttpServer)?.stopServer()
        (server as? SimpleFtpServer)?.stopServer()
        server = null
        ftpConfig = null
        running = false
        setRunningUi(false)
    }

    /** 运行中时以新配置重启服务（静默，不重复弹 Toast）。 */
    private fun restartIfRunning(silent: Boolean) {
        if (!running) return
        stopServer()
        startServer(showToast = !silent)
    }

    /**
     * 配置变化的分发：
     *  - FTP 正在运行：**就地热更新**共享目录 / 读写 / 显示隐藏（不断开连接），
     *    客户端下一条命令（如 LIST）即采用新配置；
     *  - 其它情况（HTTP 模式、或服务未运行）：按需重启。
     * 端口、协议类型的变化仍走 restartIfRunning（监听端口/协议必须重建）。
     */
    private fun applyConfigChange() {
        val cfg = ftpConfig
        if (running && selectedType == "FTP" && cfg != null && server is SimpleFtpServer) {
            val root = DocumentFile.fromTreeUri(this, pickedTreeUri!!)
            if (root != null && root.exists()) cfg.root = root
            cfg.showHidden = swHidden.isChecked
            cfg.allowWrite = swWrite.isChecked
        } else {
            restartIfRunning(silent = true)
        }
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

    /** 记忆当前使用状态到 SharedPreferences。 */
    private fun saveState() {
        // 初始化尚未完成时不写记忆，避免用默认值（HTTP）覆盖已保存的类型
        if (initializing) return
        val sp = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        sp.edit().apply {
            putString("type", selectedType)
            putString("port", etPort.text.toString())
            putBoolean("write", swWrite.isChecked)
            putBoolean("hidden", swHidden.isChecked)
            putString("dir_uri", pickedTreeUri?.toString() ?: "")
            apply()
        }
    }

    override fun onDestroy() {
        // 后台不关闭服务：Activity 销毁（旋转、切后台）时不再停止 server，
        // 使其持续运行；仅注销网络监听并保存状态。
        saveState()
        if (Build.VERSION.SDK_INT >= 24) {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            try {
                cm.unregisterNetworkCallback(networkCallback)
            } catch (e: Exception) {
                // 忽略
            }
        }
        super.onDestroy()
    }
}
