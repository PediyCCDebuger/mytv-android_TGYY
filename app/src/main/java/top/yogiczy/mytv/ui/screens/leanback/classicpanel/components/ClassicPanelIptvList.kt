package top.yogiczy.mytv.ui.screens.leanback.classicpanel.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.TvLazyListState
import androidx.tv.foundation.lazy.list.itemsIndexed
import androidx.tv.material3.ListItemDefaults
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.yogiczy.mytv.data.entities.EpgList
import top.yogiczy.mytv.data.entities.EpgList.Companion.currentProgrammes
import top.yogiczy.mytv.data.entities.EpgProgramme.Companion.progress
import top.yogiczy.mytv.data.entities.EpgProgrammeCurrent
import top.yogiczy.mytv.data.entities.Iptv
import top.yogiczy.mytv.data.entities.IptvGroup
import top.yogiczy.mytv.data.entities.IptvList
import top.yogiczy.mytv.ui.theme.LeanbackTheme
import kotlin.math.max

@Composable
fun LeanbackClassicPanelIptvList(
    modifier: Modifier = Modifier,
    iptvGroupProvider: () -> IptvGroup = { IptvGroup() },
    iptvListProvider: () -> IptvList = { IptvList() },
    epgListProvider: () -> EpgList = { EpgList() },
    initialIptvProvider: () -> Iptv = { Iptv() },
    channelNoProvider: (Iptv) -> Int = { 0 },
    onIptvSelected: (Iptv) -> Unit = {},
    onIptvFavoriteToggle: (Iptv) -> Unit = {},
    onIptvFocused: (Iptv, FocusRequester) -> Unit = { _, _ -> },
    showProgrammeProgressProvider: () -> Boolean = { false },
    @Suppress("UNUSED_PARAMETER") isFavoriteListProvider: () -> Boolean = { false },
    onUserAction: () -> Unit = {},
) {
    val iptvList = iptvListProvider()
    val initialIptv = initialIptvProvider()

    var hasFocused by rememberSaveable { mutableStateOf(!iptvList.contains(initialIptv)) }

    val listState = remember(iptvGroupProvider()) {
        TvLazyListState(
            if (hasFocused) 0
            else max(0, iptvList.indexOf(initialIptv) - 2)
        )
    }

    // 光标位置由列表自己维护。焦点固定在列表容器上，所以移动光标不会触发
    // “焦点项带入视野”的自动滚动，背景列表可以保持不动。
    var selectedIndex by remember(iptvList) {
        mutableStateOf(if (hasFocused) 0 else max(0, iptvList.indexOf(initialIptv)))
    }

    val listFocusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    val longPressHandled = remember { mutableStateOf(false) }
    var listHasFocus by remember { mutableStateOf(false) }

    // 面板刚出现时焦点节点可能还没挂到窗口上，请求一次会失败，
    // 这里重试到真正拿到焦点为止，避免按键漏到下层视频界面。
    LaunchedEffect(Unit) {
        repeat(40) {
            if (listHasFocus) return@LaunchedEffect
            runCatching { listFocusRequester.requestFocus() }
            kotlinx.coroutines.delay(50)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { _ -> onUserAction() }
    }

    LaunchedEffect(iptvList, selectedIndex) {
        iptvList.getOrNull(selectedIndex)?.let { onIptvFocused(it, listFocusRequester) }
    }

    // 当前“整屏完全可见”的索引区间；null 表示尚未完成布局
    fun fullyVisibleRange(): IntRange? {
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) return null

        var first = -1
        var last = -1
        for (item in visible) {
            val fullyVisible = item.offset >= info.viewportStartOffset &&
                    item.offset + item.size <= info.viewportEndOffset
            if (fullyVisible) {
                if (first < 0) first = item.index
                last = item.index
            }
        }

        return if (first >= 0 && last >= first) first..last else null
    }

    /**
     * 上下键选台：
     * - 目标频道仍在当前可见范围内：只移动光标，背景列表保持不动；
     * - 目标频道移出可见范围：整屏刷新一次，下移时目标频道落在列表最前，
     *   上移时目标频道落在列表最后。
     */
    fun moveCursor(delta: Int) {
        if (iptvList.isEmpty()) return

        val target = selectedIndex + delta
        if (target !in iptvList.indices) return

        val range = fullyVisibleRange()
        if (range == null || target in range) {
            selectedIndex = target
            return
        }

        // 一页 = 当前完整显示的项数 + 底部露出的那一项，
        // 这样上移翻页后光标项正好落在列表最后一项的位置
        val pageSize = max(1, range.last - range.first + 2)
        if (iptvList.size <= pageSize) {
            // 列表不足一屏，无需滚动
            selectedIndex = target
            return
        }

        val newFirst = (if (delta > 0) target else target - pageSize + 1)
            .coerceIn(0, max(0, iptvList.size - pageSize))

        selectedIndex = target
        coroutineScope.launch { listState.scrollToItem(newFirst) }
    }

    fun handleKeyEvent(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        val native = event.nativeKeyEvent

        return when (native.keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_UP,
            AndroidKeyEvent.KEYCODE_CHANNEL_UP -> {
                if (event.type == KeyEventType.KeyDown) {
                    onUserAction()
                    moveCursor(-1)
                }
                true
            }

            AndroidKeyEvent.KEYCODE_DPAD_DOWN,
            AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (event.type == KeyEventType.KeyDown) {
                    onUserAction()
                    moveCursor(1)
                }
                true
            }

            AndroidKeyEvent.KEYCODE_DPAD_CENTER,
            AndroidKeyEvent.KEYCODE_ENTER,
            AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> when {
                native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0 -> true

                native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 1 -> {
                    longPressHandled.value = true
                    onUserAction()
                    iptvList.getOrNull(selectedIndex)?.let { onIptvFavoriteToggle(it) }
                    true
                }

                native.action == AndroidKeyEvent.ACTION_UP -> {
                    if (!longPressHandled.value) {
                        onUserAction()
                        iptvList.getOrNull(selectedIndex)?.let { onIptvSelected(it) }
                    }
                    longPressHandled.value = false
                    true
                }

                else -> true
            }

            else -> false
        }
    }

    TvLazyColumn(
        state = listState,
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .focusRequester(listFocusRequester)
            .onFocusChanged {
                listHasFocus = it.isFocused
                if (it.isFocused) hasFocused = true
            }
            .focusable()
            .onPreviewKeyEvent { handleKeyEvent(it) }
            .fillMaxHeight()
            .width(220.dp)
            // 视口底部总会有半露的下一项，直接裁切会让文字只剩一排笔画（像撒了粉）。
            // 这里用 DstIn 做底部渐隐，半露内容柔和淡出到画面里。
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                val h = size.height
                if (h > 0f) {
                    val fade = 56.dp.toPx().coerceAtMost(h)
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Black,
                            ((h - fade) / h).coerceIn(0.001f, 0.999f) to Color.Black,
                            1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
            .background(MaterialTheme.colorScheme.background.copy(0.8f)),
    ) {
        itemsIndexed(iptvList, key = { _, iptv -> iptv.urlList.firstOrNull() ?: iptv.channelName }) { index, iptv ->
            LeanbackClassicPanelIptvItem(
                iptvProvider = { iptv },
                channelNoProvider = { channelNoProvider(iptv) },
                epgProgrammeCurrentProvider = { epgListProvider().currentProgrammes(iptv) },
                isSelectedProvider = { index == selectedIndex },
                showProgrammeProgressProvider = showProgrammeProgressProvider,
                // 鼠标点击选台：先落光标再切台，键盘与鼠标共用同一套选中状态
                onClick = {
                    onUserAction()
                    selectedIndex = index
                    onIptvSelected(iptv)
                },
            )
        }
    }
}

@Composable
private fun LeanbackClassicPanelIptvItem(
    modifier: Modifier = Modifier,
    iptvProvider: () -> Iptv = { Iptv() },
    channelNoProvider: () -> Int = { 0 },
    epgProgrammeCurrentProvider: () -> EpgProgrammeCurrent? = { null },
    isSelectedProvider: () -> Boolean = { false },
    showProgrammeProgressProvider: () -> Boolean = { false },
    onClick: () -> Unit = {},
) {
    val iptv = iptvProvider()
    val channelNo = channelNoProvider()
    val isSelected = isSelectedProvider()
    // 点击回调随重组变化（索引会变），用最新引用，避免手势协程里拿到旧闭包
    val currentOnClick by rememberUpdatedState(onClick)
    // 用“分钟桶”作为记忆键：避免每次重组都扫描 EPG（解决打开/滚动卡顿），
    // 同时每分钟刷新一次“正在播放”，兼顾流畅与实时
    val minuteBucket = System.currentTimeMillis() / 60_000L
    val currentProgramme = remember(iptv, minuteBucket) {
        epgProgrammeCurrentProvider()?.now
    }

    CompositionLocalProvider(
        LocalContentColor provides if (isSelected) MaterialTheme.colorScheme.background
        else MaterialTheme.colorScheme.onBackground
    ) {
        Box(
            modifier = Modifier
                .clip(ListItemDefaults.shape().shape)
                // 点击选台由这一层统一接管：在 Initial 阶段取事件，
                // 父级先于子级拿到指针，不受列表项内部点击实现（以及 canFocus=false）影响。
                // 拿到 up 后 consume，保证同一次点击只触发一次。
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                        if (up != null) {
                            up.consume()
                            currentOnClick()
                        }
                    }
                },
        ) {
            androidx.tv.material3.ListItem(
                modifier = modifier
                    .height(64.dp)
                    // 列表项不再自己持有焦点：光标由容器统一维护，避免自动滚动
                    .focusProperties { canFocus = false },
                colors = ListItemDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.onBackground,
                    selectedContainerColor = MaterialTheme.colorScheme.onBackground,
                ),
                selected = isSelected,
                // 点击由外层 pointerInput 在 Initial 阶段接管，这里保持空实现，避免一次点击走两条路径
                onClick = { },
                headlineContent = {
                    Text(
                        text = if (channelNo > 0)
                            String.format("%02d  %s", channelNo, iptv.name)
                        else iptv.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Text(
                        text = currentProgramme?.title ?: "无节目",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        modifier = Modifier.alpha(0.8f),
                    )
                },
            )

            if (showProgrammeProgressProvider() && currentProgramme != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(currentProgramme.progress())
                        .height(3.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                        ),
                )
            }
        }
    }
}

@Preview
@Composable
private fun LeanbackClassicPanelIptvListPreview() {
    LeanbackTheme {
        LeanbackClassicPanelIptvList(
            modifier = Modifier.padding(20.dp),
            iptvListProvider = { IptvList.EXAMPLE },
        )
    }
}
