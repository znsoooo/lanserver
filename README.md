# 局域网服务器（LAN Server）

一个轻量的安卓局域网文件共享工具：把手机上某个目录通过 **HTTP** 或 **FTP** 暴露到局域网，
同一 Wi-Fi 下的电脑/手机用浏览器或 FTP 客户端即可访问、下载该目录里的文件。

包名：`com.example.lanserver` ｜ `minSdk 21` ｜ `targetSdk / compileSdk 34`

## 一、功能与界面

打开 App 后界面自上而下依次为：

| 行 | 内容 | 说明 |
|----|------|------|
| 1 | **本机地址**（IP + 端口） | 自动读取手机首个可用 IPv4 地址，右侧为端口输入框（默认 `8888`）；访问地址随 IP/端口/类型变化实时刷新 |
| 2 | **服务器类型** | 分段按钮点选 `FTP` 或 `HTTP`（选中项高亮为蓝青渐变） |
| 3 | **访问网址** | 实时显示当前访问地址，点右侧「复制」按钮复制 |
| 4 | **共享目录** + 「选择」按钮 + 路径文本 | 点「选择」调起系统文件夹选择器（SAF）；选中后显示**真实存储路径**（如 `/storage/emulated/0/Download/分享保存`），点右侧「复制」按钮复制 |
| 5 | **允许写入** 开关 | 默认关闭；开启后 HTTP 模式可在网页上传文件、FTP 模式允许 STOR/MKD/RMD/DELE 写入操作 |
| 6 | **显示隐藏文件** 开关 | 默认关闭；开启后名称以 `.` 开头的文件/文件夹也会在列表中显示 |
| 7 | **启动服务** 按钮 | 点一下启动服务（再次点击变「停止服务」）；访问地址与二维码**始终显示** |
| 8 | **访问地址二维码** | 始终显示，扫码即可在另一台设备打开 |

目录列表的**排序规则**：文件夹排在文件前面，同级按名称字母序（**不区分大小写**）排列。

## 二、使用步骤

1. 手机连接到 Wi-Fi / 局域网（需与访问端在同一网段）。
2. 点「选择目录」挑选要共享的文件夹；界面会显示其真实路径。
3. 选择服务器类型（HTTP 或 FTP）、确认端口（默认 8888）。
4. 点「启动服务」，记下界面显示的访问地址，或扫二维码。
5. 在电脑浏览器 / FTP 客户端访问该地址：
   - **HTTP 模式**：浏览器直接打开 `http://手机IP:端口/`，可浏览目录、点击文件下载；开启「允许写入」后，目录页面右上角出现「上传文件」按钮，选择文件即上传到**当前所在目录**。
   - **FTP 模式**：用 FTP 客户端连接 `ftp://手机IP:端口/`（匿名登录，支持被动/主动模式、列目录、下载、上传）；仅当开启「允许写入」时允许 STOR/MKD/RMD/DELE 写入操作，否则服务器拒绝写入并返回 550。

> 访问地址会随「本机 IP / 端口 / 类型」变化实时刷新；IP 变化时重新进入 App 即可更新。

## 三、关于目录路径（`primary:`、`/tree`、`/document` 是什么）

安卓从 Android 5.0 起，访问外部存储必须通过 **SAF（存储访问框架）**，App 拿到的不是
`/storage/emulated/0/...` 这种真实路径，而是一个 `content://` 形式的 **Content URI**，例如：

```
content://com.android.externalstorage.documents/tree/primary:Download/分享保存
```

其中：

- **`primary:`** —— 代表设备**主存储卷**，即 `/storage/emulated/0/`。所以
  `primary:Download/分享保存` 对应的真实路径就是 `/storage/emulated/0/Download/分享保存`。
- **`/tree/...`** —— 表示这是一个「目录树」授权 URI（来自「选择目录」授权）。
- **`/document/...`** —— 表示树下的某个具体文件/子目录节点。

直接把这种 URI 展示给用户会很迷惑，因此本 App 会把选中的目录**还原成可读的真实存储路径**再显示
（见 `DocUtils.friendlyPath()`）。若你之前看到过带 `/document/` 的冗长字符串，那是 SAF 的
Content URI 原样输出，并非文件系统路径——现在已修正为友好路径。

## 四、注意事项与已知限制

- **防火墙 / 厂商限制**：部分 ROM 会拦截非特权端口的入站连接。若连不上，可换端口，
  或检查该 App 的「网络权限 / 后台数据 / 不受限制的数据」等设置。
- **HTTP 为简化实现**：自实现轻量 HTTP/1.0，支持目录浏览与文件下载、常见类型 MIME，
  开启「允许写入」后支持 `multipart/form-data` 网页上传；不带鉴权，仅适合**可信局域网内**临时共享。
- **FTP 为简化实现**：匿名、无密码鉴权，支持被动（PASV）/主动（PORT）、
  `LIST / CWD / PWD / RETR / STOR / SIZE` 等常用命令；`STOR/MKD/RMD/DELE` 写入命令仅在「允许写入」开启时可用；
  不支持断点续传等高级特性。
  个别手机浏览器不能直接访问 `ftp://` 地址（受浏览器自身限制），建议用 FTP 客户端。
- **服务存活**：服务器运行在前台线程，App 进程被系统强杀后服务会停止。
  如需后台长期常驻，可后续改为前台服务（参考同类做法）。
- 目录选择通过 `ACTION_OPEN_DOCUMENT_TREE` 并持久化授权，重启后无需重新选目录。

## 五、编译方法

### 环境要求

- **JDK 17**（或兼容版本）
- **Android SDK**（`ANDROID_HOME` 指向 SDK 目录），需含 **SDK Platform 34** 与 **build-tools**
- **Gradle 8.2**

### 步骤

```bash
# 1. 设置 Android SDK 路径
export ANDROID_HOME=/opt/android-sdk      # 按你的 SDK 实际位置修改

# 2. 进入工程目录
cd lanserver

# 3. 编译 Debug 包（产物名为 lanserver-debug.apk）
/root/gradle-8.2/bin/gradle clean assembleDebug --no-daemon

# 4. 取产物
ls app/build/outputs/apk/debug/lanserver-debug.apk
```


### 主要源码结构

```
app/src/main/
├── AndroidManifest.xml
├── java/com/example/lanserver/
│   ├── MainActivity.kt        # UI 与服务器启停
│   ├── SimpleHttpServer.kt    # 自实现轻量 HTTP 服务器
│   ├── SimpleFtpServer.kt     # 自实现轻量 FTP 服务器
│   ├── DocUtils.kt            # 路径解析/排序/隐藏过滤/友好路径
│   ├── IpUtils.kt             # 本机 IP 获取
│   └── QrUtils.kt             # 二维码生成（ZXing）
└── res/layout/activity_main.xml
```

### 依赖

- `com.google.zxing:core:3.5.2` —— 二维码生成
- `androidx.documentfile:documentfile:1.0.1` —— SAF 目录遍历
