package top.yogiczy.mytv.ui.screens.leanback.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.items
import top.yogiczy.mytv.data.utils.Constants
import top.yogiczy.mytv.ui.screens.leanback.settings.LeanbackSettingsViewModel
import top.yogiczy.mytv.ui.theme.LeanbackTheme
import top.yogiczy.mytv.ui.utils.SP
import top.yogiczy.mytv.ui.utils.handleLeanbackKeyEvents

private fun proxyLabel(proxy: String) = if (proxy.isBlank()) "直连（不使用代理）" else proxy

@Composable
fun LeanbackSettingsCategoryUpdate(
    modifier: Modifier = Modifier,
    settingsViewModel: LeanbackSettingsViewModel = viewModel(),
) {
    var showProxyDialog by remember { mutableStateOf(false) }
    var proxy by remember { mutableStateOf(SP.updateProxy) }

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
            // GitHub 直连不稳时可选公共代理；点击弹出下拉列表选择。
            LeanbackSettingsCategoryListItem(
                headlineContent = "更新下载代理",
                supportingContent = "${proxyLabel(proxy)}　（点击选择，共 ${Constants.GITHUB_PROXIES.size} 项）",
                onSelected = {
                    proxy = SP.updateProxy
                    showProxyDialog = true
                },
            )
        }
    }

    LeanbackUpdateProxyDialog(
        showDialogProvider = { showProxyDialog },
        currentProxyProvider = { proxy },
        onDismissRequest = { showProxyDialog = false },
        onSelected = { selected ->
            proxy = selected
            SP.updateProxy = selected
            showProxyDialog = false
        },
    )
}

@Composable
private fun LeanbackUpdateProxyDialog(
    modifier: Modifier = Modifier,
    showDialogProvider: () -> Boolean = { false },
    currentProxyProvider: () -> String = { "" },
    onDismissRequest: () -> Unit = {},
    onSelected: (String) -> Unit = {},
) {
    if (!showDialogProvider()) return

    val proxies = Constants.GITHUB_PROXIES
    val currentProxy = currentProxyProvider()

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        confirmButton = { Text(text = "选择下载代理") },
        title = { Text("更新下载代理") },
        text = {
            TvLazyColumn(
                state = androidx.tv.foundation.lazy.list.TvLazyListState(
                    maxOf(0, proxies.indexOf(currentProxy) - 2),
                ),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(proxies) { item ->
                    val focusRequester = remember { FocusRequester() }
                    var isFocused by remember { mutableStateOf(false) }

                    LaunchedEffect(Unit) {
                        if (item == currentProxy) {
                            focusRequester.requestFocus()
                        }
                    }

                    androidx.tv.material3.ListItem(
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .onFocusChanged { isFocused = it.isFocused || it.hasFocus }
                            .handleLeanbackKeyEvents(
                                onSelect = {
                                    if (isFocused) onSelected(item)
                                    else focusRequester.requestFocus()
                                },
                            ),
                        selected = currentProxy == item,
                        onClick = {},
                        headlineContent = {
                            androidx.tv.material3.Text(
                                text = proxyLabel(item),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        trailingContent = {
                            if (currentProxy == item) {
                                androidx.tv.material3.Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "checked",
                                )
                            }
                        },
                    )
                }
            }
        },
    )
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
