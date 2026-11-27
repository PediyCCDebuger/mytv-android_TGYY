package top.yogiczy.mytv.data.repositories.git.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.yogiczy.mytv.data.entities.GitRelease
import top.yogiczy.mytv.ui.utils.SP

class GithubGitReleaseParser : GitReleaseParser {
    override fun isSupport(url: String): Boolean {
        return url.contains("github.com")
    }

    override suspend fun parse(data: String): GitRelease {
        val json = Json.parseToJsonElement(data).jsonObject
        val assets = json.getValue("assets").jsonArray

        // 优先 universal 包（任何设备都能装），其次 arm64，最后任取一个 apk。
        fun pickAsset(predicate: (String) -> Boolean): String? {
            for (i in 0 until assets.size) {
                val name = assets[i].jsonObject["name"]?.jsonPrimitive?.content ?: continue
                if (name.endsWith(".apk", ignoreCase = true) && predicate(name)) {
                    return assets[i].jsonObject["browser_download_url"]?.jsonPrimitive?.content
                }
            }
            return null
        }
        val rawUrl = pickAsset { it.contains("universal", ignoreCase = true) }
            ?: pickAsset { it.contains("arm64", ignoreCase = true) }
            ?: pickAsset { true }
            ?: ""

        // 用户选择的下载代理：https://<proxy>/<原始直连链接>；直连时空串不加前缀。
        val proxy = SP.updateProxy
        val downloadUrl = if (proxy.isBlank()) rawUrl else "https://$proxy/$rawUrl"

        return GitRelease(
            version = json.getValue("tag_name").jsonPrimitive.content.substring(1),
            downloadUrl = downloadUrl,
            description = json.getValue("body").jsonPrimitive.content
        )
    }
}
