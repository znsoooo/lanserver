package com.znsoooo.lanserver

import androidx.documentfile.provider.DocumentFile

/**
 * 服务运行期配置，HTTP 与 FTP **共用同一份**。
 *
 * 三个字段都是 @Volatile，且被各连接在**每次操作时实时读取**（不做会话快照）。
 * 因此在界面修改「共享目录 / 允许写入 / 显示隐藏」后，已经建立的连接在下一条
 * 请求或命令上即采用新配置，无需断开重连。
 *
 * 合并为单端口后，这套配置同时作用于 HTTP 与 FTP，两条协议路径的取值保持一致。
 */
class ServerConfig(
    @Volatile var root: DocumentFile,
    @Volatile var showHidden: Boolean,
    @Volatile var allowWrite: Boolean
)
