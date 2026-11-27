package top.yogiczy.mytv.data.utils

/**
 * 常量
 */
object Constants {
    /**
     * 应用 标题
     */
    const val APP_TITLE = "我的电视"

    /**
     * 应用 代码仓库（TGYY 改造版）
     */
    const val APP_REPO = "https://github.com/PediyCCDebuger/mytv-android_TGYY"

    /**
     * IPTV源地址
     */
    const val IPTV_SOURCE_URL = "http://127.0.0.1:8767/all.m3u"

    /**
     * IPTV源缓存时间（毫秒）
     */
    const val IPTV_SOURCE_CACHE_TIME = 1000 * 60 * 60 * 24L // 24小时

    /**
     * 节目单XML地址
     */
    // 默认节目单：覆盖央视/卫视等国内频道；与 M3uIptvParser 用 tvg-id 匹配 display-name
    const val EPG_XML_URL = "https://epg.136605.xyz/3days.xml.gz"

    /**
     * 节目单刷新时间阈值（小时）
     */
    const val EPG_REFRESH_TIME_THRESHOLD = 2 // 不到2点不刷新

    /**
     * Git最新版本信息（TGYY 改造版仓库）
     */
    const val GIT_RELEASE_LATEST_URL =
        "https://api.github.com/repos/PediyCCDebuger/mytv-android_TGYY/releases/latest"

    /**
     * GitHub 下载代理：第一项为直连，其后为公共反向代理。
     * 拼法为 https://<代理>/<原始 browser_download_url>。
     * 用户可在「设置 → 更新 → 更新下载代理」中循环切换。
     */
    val GITHUB_PROXIES = listOf(
        "",                          // 直连
        "gh-proxy.org",
        "gh-proxy.com",
        "ghfast.top",
        "gh.acmsz.top",
        "github.chenc.dev",
        "gh.monlor.com",
    )

    /**
     * GitHub加速代理地址（兼容旧引用；默认直连，运行时由偏好覆盖）
     */
    const val GITHUB_PROXY = ""

    /**
     * HTTP请求重试次数
     */
    const val HTTP_RETRY_COUNT = 10L

    /**
     * HTTP请求重试间隔时间（毫秒）
     */
    const val HTTP_RETRY_INTERVAL = 3000L

    /**
     * 播放器 userAgent
     */
    const val VIDEO_PLAYER_USER_AGENT = "ExoPlayer"

    /**
     * 日志历史最大保留条数
     */
    const val LOG_HISTORY_MAX_SIZE = 50

    /**
     * 播放器加载超时
     */
    const val VIDEO_PLAYER_LOAD_TIMEOUT = 1000L * 30 // 30秒：内核冷拉最长15s+3次IO重试约9s，20s会误弹播放失败

    /**
     * 界面 超时未操作自动关闭界面
     */
    const val UI_SCREEN_AUTO_CLOSE_DELAY = 1000L * 15 // 15秒

    /**
     * 界面 时间显示前后范围
     */
    const val UI_TIME_SHOW_RANGE = 1000L * 30 // 前后30秒

    /**
     * 界面 临时面板界面显示时间
     */
    const val UI_TEMP_PANEL_SCREEN_SHOW_DURATION = 1500L // 1.5秒
}