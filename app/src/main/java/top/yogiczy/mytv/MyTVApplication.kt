package top.yogiczy.mytv

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import top.yogiczy.mytv.ui.utils.SP

class MyTVApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 防止内嵌 Python 守护线程 / 后台 IO 协程抛出未捕获异常时直接杀死整个进程，
        // 否则会出现“应用刚启动就闪退”的现象（主线程崩溃仍按系统默认上报，便于暴露真正的 UI 错误）
        installBackgroundCrashGuard()

        UnsafeTrustManager.enableUnsafeTrustManager()
        AppGlobal.cacheDir = applicationContext.cacheDir
        SP.init(applicationContext)
        startYspLiveService()
    }

    /**
     * 后台线程的未捕获异常只记录、不杀进程；主线程(main)仍走系统默认崩溃流程。
     */
    private fun installBackgroundCrashGuard() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val name = thread.name
            if (name == "main" || name.startsWith("main-") || name.startsWith("android.ui")) {
                defaultHandler?.uncaughtException(thread, throwable)
            } else {
                Log.e(
                    "MyTV",
                    "后台线程未捕获异常(已隔离，避免进程被杀): $name",
                    throwable,
                )
            }
        }
    }

    private fun startYspLiveService() {
        try {
            val intent = Intent(this, YspLiveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            // 启动失败不影响主功能，用户仍可在设置中手动添加其他订阅源
        }
    }
}
