package top.yogiczy.mytv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 本机 ysp-live 直播源服务（前台服务）
 *
 * 通过 Chaquopy 在应用进程内启动内嵌的 ysp-live Python 服务，
 * 监听 127.0.0.1:8767，向本应用提供 /all.m3u 等央视/卫视直播源。
 * 使用 v20261124 内核：设备协议为纯 Python 实现（无需 Node），启用后
 * 26 个高码率频道 + 4K/8K 可走设备源流畅播放；设备凭证从 assets 释放，
 * 避免现场云注册失败。非设备频道以 bk 主路 + JCE 兜底，规避电视端僵死。
 */
class YspLiveService : Service() {
    companion object {
        private const val TAG = "YspLiveService"
        const val CHANNEL_ID = "ysp_live_channel"
        const val NOTIFICATION_ID = 8767
        const val PORT = 8767
        private var pythonStarted = false
    }

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification("ysp-live 本地服务启动中…"))
        } catch (e: Exception) {
            // 通知渠道/前台服务创建失败（部分系统或权限受限）不应拖垮主应用
            Log.e(TAG, "前台服务/通知创建失败(非致命): ${e.message}", e)
        }

        CoroutineScope(
            Dispatchers.IO + CoroutineExceptionHandler { _, e ->
                Log.e(TAG, "ysp-live 协程异常(已隔离，不影响主应用): ${e.message}", e)
            }
        ).launch {
            try {
                if (!pythonStarted) {
                    try {
                        Python.start(AndroidPlatform(this@YspLiveService))
                    } catch (_: Exception) {
                        // 同一进程内已初始化则忽略
                    }
                    pythonStarted = true
                }
                // 将内置的已注册设备凭证（含 cloud_guid）释放到 data 目录，
                // 供高码率设备协议免云注册直接激活；仅在文件不存在时释放，
                // 避免覆盖内核后续写入的刷新状态。
                copyDeviceCredentials()

                val py = Python.getInstance()
                val module = py.getModule("ysplive")
                module.callAttr(
                    "start_server",
                    PORT,            // port
                    "127.0.0.1",     // bind
                    false,           // no_4k=false：启用设备协议，播放高码率/4K 频道
                    true,            // no_engine（无 Node，设备协议为纯 Python，无需伴生引擎）
                    8787,            // engine_port
                    64,              // cache_mb
                    6,               // links_per_device
                    10.0,            // zap_debounce
                    10.0,            // timeout
                    filesDir.absolutePath, // data_dir
                )
                updateNotification("ysp-live 已就绪：央视/卫视 + 高码率/4K 本机服务 (端口 $PORT)")
            } catch (e: Exception) {
                updateNotification("ysp-live 启动失败：${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            if (pythonStarted) {
                Python.getInstance().getModule("ysplive").callAttr("stop_server")
            }
        } catch (_: Exception) {
            // 忽略停止异常
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 释放内置设备凭证到 filesDir。这些文件含已注册的 cloud_guid，
     * 高码率设备协议据此直接激活，避免现场云注册失败（empty guid）。
     *
     * 凭证有有效期：旧 cloud_guid 被服务端废弃后，高码率分片会 403。
     * 因此用 assets/ysp/credential-version.txt 标记版本：仅当内置版本高于
     * filesDir 中已释放的版本时才覆盖，版本相同时保留内核运行时更新的
     * 心跳/刷新状态（包括内核自动更换的新身份）。
     */
    private fun copyDeviceCredentials() {
        val names = arrayOf(
            "device-state-rs.json",
            "device-state-rs-slot1.json",
            "device-state-rs-slot2.json",
        )
        val markerName = "credential-version.txt"
        val assetVersion = try {
            assets.open("ysp/$markerName").bufferedReader().use { it.readText().trim().toIntOrNull() ?: 1 }
        } catch (_: Exception) {
            1
        }
        val marker = File(filesDir, markerName)
        val currentVersion = try {
            if (marker.exists()) marker.readText().trim().toIntOrNull() ?: 0 else 0
        } catch (_: Exception) {
            0
        }
        val needRefresh = currentVersion < assetVersion || names.any { !File(filesDir, it).exists() }
        for (name in names) {
            try {
                val target = File(filesDir, name)
                if (target.exists() && !needRefresh) continue
                assets.open("ysp/$name").use { input ->
                    FileOutputStream(target).use { out -> input.copyTo(out) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "释放设备凭证 $name 失败: ${e.message}")
            }
        }
        if (needRefresh) {
            try {
                marker.writeText(assetVersion.toString())
                Log.i(TAG, "内置设备凭证已更新到版本 $assetVersion")
            } catch (e: Exception) {
                Log.w(TAG, "写入凭证版本标记失败: ${e.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ysp-live 本地直播服务",
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ysp-live 本地服务")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
