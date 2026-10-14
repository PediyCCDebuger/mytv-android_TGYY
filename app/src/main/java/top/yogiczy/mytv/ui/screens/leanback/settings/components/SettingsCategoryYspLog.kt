package top.yogiczy.mytv.ui.screens.leanback.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.items
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import top.yogiczy.mytv.ui.theme.LeanbackTheme
import java.util.concurrent.TimeUnit

private const val YSP_LOG_URL = "http://127.0.0.1:8767/log"

/**
 * ysp-live 本地服务日志与运行状态
 *
 * 轮询本机 ysp-live 的 /log 接口，展示其启动与运行日志，
 * 便于排查央视/卫视直播源无法播放等问题。服务未运行时给出明确提示。
 */
@Composable
fun LeanbackSettingsCategoryYspLog(
    modifier: Modifier = Modifier,
) {
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf("正在连接 ysp-live 本机服务…") }
    var connected by remember { mutableStateOf(false) }

    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                val bodyText = withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(YSP_LOG_URL).build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            response.body?.string() ?: ""
                        } else {
                            throw java.io.IOException("HTTP ${response.code}")
                        }
                    }
                }
                val json = JSONObject(bodyText)
                val count = json.optInt("count", 0)
                val uptime = json.optInt("uptime_sec", 0)
                val engineReady = json.optBoolean("engine_ready", false)
                val arr: JSONArray = json.optJSONArray("lines") ?: JSONArray()
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    list.add(arr.optString(i))
                }
                lines = list
                connected = true
                status = buildString {
                    append("已连接 · 共 $count 条日志 · 运行 ${uptime}s")
                    append(
                        if (engineReady) {
                            " · 引擎就绪"
                        } else {
                            " · 移动端模式(4K自动回退1080p)"
                        }
                    )
                }
            } catch (e: Exception) {
                connected = false
                status = "无法连接 ysp-live 本机服务（127.0.0.1:8767）：${e.message ?: e.javaClass.simpleName}。请确认应用已正常启动，或在“直播源”中改用其他订阅源。"
            }
            delay(2000)
        }
    }

    TvLazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 10.dp),
    ) {
        item {
            LeanbackSettingsCategoryListItem(
                headlineContent = "ysp-live 状态",
                supportingContent = status,
                trailingContent = if (connected) "在线" else "离线",
            )
        }

        items(lines) { line ->
            LeanbackSettingsCategoryListItem(
                modifier = Modifier.fillMaxWidth(),
                headlineContent = line,
            )
        }
    }
}

@Preview
@Composable
private fun LeanbackSettingsCategoryYspLogPreview() {
    LeanbackTheme {
        LeanbackSettingsCategoryYspLog(
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
