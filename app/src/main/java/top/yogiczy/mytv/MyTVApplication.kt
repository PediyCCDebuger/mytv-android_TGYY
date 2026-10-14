package top.yogiczy.mytv

import android.app.Application
import android.content.Intent
import android.os.Build
import top.yogiczy.mytv.ui.utils.SP

class MyTVApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        UnsafeTrustManager.enableUnsafeTrustManager()
        AppGlobal.cacheDir = applicationContext.cacheDir
        SP.init(applicationContext)
        startYspLiveService()
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
