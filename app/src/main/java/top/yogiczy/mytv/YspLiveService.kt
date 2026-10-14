package top.yogiczy.mytv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 本机 ysp-live 直播源服务（前台服务）
 *
 * 通过 Chaquopy 在应用进程内启动内嵌的 ysp-live Python 服务，
 * 监听 127.0.0.1:8767，向本应用提供 /all.m3u 等央视/卫视直播源。
 * 移动端模式固定关闭设备协议（no_4k），4K/高码率频道自动回退 1080p，
 * 以保证在无 Node 运行时的环境下稳定可用。
 */
class YspLiveService : Service() {
    companion object {
        const val CHANNEL_ID = "ysp_live_channel"
        const val NOTIFICATION_ID = 8767
        const val PORT = 8767
        private var pythonStarted = false
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("ysp-live 本地服务启动中…"))

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!pythonStarted) {
                    try {
                        Python.start(AndroidPlatform(this@YspLiveService))
                    } catch (_: Exception) {
                        // 同一进程内已初始化则忽略
                    }
                    pythonStarted = true
                }
                val py = Python.getInstance()
                val module = py.getModule("ysplive")
                module.callAttr(
                    "start_server",
                    PORT,            // port
                    "127.0.0.1",     // bind
                    true,            // no_4k（移动端关闭设备协议，4K 回退 1080p）
                    true,            // no_engine（无 Node，不启动 WASM 伴生引擎）
                    8787,            // engine_port
                    64,              // cache_mb
                    6,               // links_per_device
                    10.0,            // zap_debounce
                    10.0,            // timeout
                    filesDir.absolutePath, // data_dir
                )
                updateNotification("ysp-live 已就绪：央视/卫视 直播源本机服务 (端口 $PORT)")
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
