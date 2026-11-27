package top.yogiczy.mytv.ui.screens.leanback.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.foundation.lazy.list.TvLazyColumn
import top.yogiczy.mytv.data.utils.Constants
import top.yogiczy.mytv.ui.screens.leanback.settings.LeanbackSettingsViewModel
import top.yogiczy.mytv.ui.theme.LeanbackTheme
import top.yogiczy.mytv.ui.utils.SP

@Composable
fun LeanbackSettingsCategoryUpdate(
    modifier: Modifier = Modifier,
    settingsViewModel: LeanbackSettingsViewModel = viewModel(),
) {
    TvLazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 10.dp),
    ) {
        item {
            LeanbackSettingsCategoryListItem(
                headlineContent = "更新强提醒",
                supportingContent = if (settingsViewModel.updateForceRemind) "检测到新版本时会弹窗提醒"
                else "检测到新版本时仅在左上角提示",
                trailingContent = {
                    Switch(checked = settingsViewModel.updateForceRemind, onCheckedChange = null)
                },
                onSelected = {
                    settingsViewModel.updateForceRemind = !settingsViewModel.updateForceRemind
                },
            )
        }
        item {
            // GitHub 直连不稳时可选公共代理；点击循环切换，改动即时生效（下次检查更新用）。
            var proxy by remember { mutableStateOf(SP.updateProxy) }
            val proxyLabel = if (proxy.isBlank()) "直连（不使用代理）" else proxy
            LeanbackSettingsCategoryListItem(
                headlineContent = "更新下载代理",
                supportingContent = "$proxyLabel　（点击切换，共 ${Constants.GITHUB_PROXIES.size} 项）",
                onSelected = {
                    val idx = Constants.GITHUB_PROXIES.indexOf(proxy)
                    proxy = Constants.GITHUB_PROXIES[(idx + 1) % Constants.GITHUB_PROXIES.size]
                    SP.updateProxy = proxy
                },
            )
        }
    }
}

@Preview
@Composable
private fun LeanbackSettingsCategoryUpdatePreview() {
    LeanbackTheme {
        LeanbackSettingsCategoryUpdate(
            modifier = Modifier.padding(20.dp),
        )
    }
}
