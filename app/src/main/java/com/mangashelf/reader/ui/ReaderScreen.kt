package com.mangashelf.reader.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.mangashelf.reader.MangaShelfApp
import com.mangashelf.reader.data.Book
import com.mangashelf.reader.ui.theme.ComicScrim
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(bookId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MangaShelfApp
    val viewModel: ReaderViewModel = viewModel(
        key = "reader-$bookId",
        factory = ReaderViewModelFactory(app.repository, bookId)
    )
    val book by viewModel.book.collectAsStateWithLifecycle()

    val currentBook = book
    if (currentBook == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    } else {
        ReaderContent(viewModel = viewModel, book = currentBook, onBack = onBack)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReaderContent(viewModel: ReaderViewModel, book: Book, onBack: () -> Unit) {
    val pages = remember(book.folder, book.pageCount) { viewModel.pagePaths(book) }

    if (pages.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text("这本书没有可显示的页面", color = Color.White)
        }
        return
    }

    val pageCount = pages.size
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(
        initialPage = book.lastPage.coerceIn(0, pageCount - 1),
        pageCount = { pageCount }
    )
    var chromeVisible by remember { mutableStateOf(true) }
    var zoomedIn by remember { mutableStateOf(false) }
    var showJumpDialog by remember { mutableStateOf(false) }
    var showPagePicker by remember { mutableStateOf(false) }

    ImmersiveMode()

    LaunchedEffect(pager.currentPage) {
        zoomedIn = false
        viewModel.saveProgress(pager.currentPage)
    }

    BackHandler {
        if (!chromeVisible) chromeVisible = true else onBack()
    }

    val goTo: (Int) -> Unit = { target ->
        val clamped = target.coerceIn(0, pageCount - 1)
        if (clamped != pager.currentPage) {
            scope.launch { pager.animateScrollToPage(clamped) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            reverseLayout = book.rtl,
            userScrollEnabled = !zoomedIn
        ) { index ->
            ZoomablePage(
                path = pages[index],
                isCurrent = index == pager.currentPage,
                onZoomChanged = { zoomed -> if (index == pager.currentPage) zoomedIn = zoomed },
                onTapAt = { fraction ->
                    if (!zoomedIn) {
                        when {
                            // 从右往左阅读时，左侧是"下一页"
                            fraction < 0.32f ->
                                if (book.rtl) goTo(pager.currentPage + 1) else goTo(pager.currentPage - 1)

                            fraction > 0.68f ->
                                if (book.rtl) goTo(pager.currentPage - 1) else goTo(pager.currentPage + 1)

                            else -> chromeVisible = !chromeVisible
                        }
                    }
                }
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(ComicScrim, Color.Transparent)))
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = "返回书架",
                        tint = Color.White
                    )
                }
                Text(
                    text = book.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // 页码做成可点的按钮：点它就能输入页码直接跳过去
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.14f))
                        .clickable { showPagePicker = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "${pager.currentPage + 1} / $pageCount",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, ComicScrim)))
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                if (pageCount > 1) {
                    Slider(
                        value = pager.currentPage.toFloat(),
                        onValueChange = { value ->
                            val target = value.roundToInt().coerceIn(0, pageCount - 1)
                            scope.launch { pager.scrollToPage(target) }
                        },
                        valueRange = 0f..(pageCount - 1).toFloat()
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (book.rtl) "阅读方向：从右往左" else "阅读方向：从左往右",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.75f)
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showPagePicker = true }) {
                        Text("跳页", color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = { viewModel.setRtl(!book.rtl) }) {
                        Text(
                            text = if (book.rtl) "改为从左往右" else "改为从右往左",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        if (showPagePicker) {
            PagePicker(
                pages = pages,
                currentPage = pager.currentPage,
                onDismiss = { showPagePicker = false },
                onPick = { target ->
                    showPagePicker = false
                    goTo(target)
                },
                onTypePage = { showJumpDialog = true }
            )
        }
    }
    if (showJumpDialog) {
        PageJumpDialog(
            pageCount = pageCount,
            currentPage = pager.currentPage,
            onDismiss = { showJumpDialog = false },
            onJump = { target ->
                showJumpDialog = false
                showPagePicker = false
                goTo(target)
            }
        )
    }
}

/**
 * 单页图片：双指缩放 / 双指拖动 / 双击缩放 / 单击分区翻页。
 *
 * 只在"缩放中"才消费手势，这样单指拖动仍然交给 HorizontalPager 翻页，
 * 避免缩放与翻页抢手势。
 */
@Composable
private fun ZoomablePage(
    path: String,
    isCurrent: Boolean,
    onZoomChanged: (Boolean) -> Unit,
    onTapAt: (Float) -> Unit
) {
    var scale by remember(path) { mutableStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    LaunchedEffect(scale) {
        onZoomChanged(scale > 1.01f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewSize = it }
            .pointerInput(path) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (pressed > 1 || scale > 1.01f) {
                            val zoom = if (pressed > 1) event.calculateZoom() else 1f
                            val pan = event.calculatePan()
                            val next = (scale * zoom).coerceIn(1f, 4f)
                            scale = next
                            offset = if (next <= 1.01f) {
                                Offset.Zero
                            } else {
                                clampOffset(offset + pan, viewSize, next)
                            }
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .pointerInput(path) {
                detectTapGestures(
                    onTap = { position ->
                        val width = if (viewSize.width == 0) 1 else viewSize.width
                        onTapAt((position.x / width.toFloat()).coerceIn(0f, 0.999f))
                    },
                    onDoubleTap = {
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            offset = Offset.Zero
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = File(path),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
    }
}

private fun clampOffset(offset: Offset, size: IntSize, scale: Float): Offset {
    val maxX = (size.width * (scale - 1f) / 2f).coerceAtLeast(0f)
    val maxY = (size.height * (scale - 1f) / 2f).coerceAtLeast(0f)
    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY)
    )
}

/** 阅读时进入沉浸模式，退出阅读时恢复状态栏 */
@Composable
private fun ImmersiveMode() {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * 跳页对话框：输入页码直接跳到那一页。
 *
 * 底部滑杆在几百页的书里很难精确拖到某一页，所以再加一个能直接输数字的入口：
 * 顶部的页码按钮和底部的「跳页」按钮都会打开它。
 */
@Composable
private fun PageJumpDialog(
    pageCount: Int,
    currentPage: Int,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit
) {
    val initial = (currentPage + 1).toString()
    var field by remember {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    var error by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    fun submit() {
        val n = field.text.trim().toIntOrNull()
        if (n == null || n < 1 || n > pageCount) {
            error = "请输入 1 – $pageCount 之间的页码"
            return
        }
        onJump(n - 1)
    }

    // 弹出就把光标放进输入框，并且全选已有页码，直接打字即可替换
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳转到指定页") },
        text = {
            Column {
                Text(
                    text = "共 $pageCount 页。输入页码后点「跳转」，或按键盘上的「完成」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = field,
                    onValueChange = { value ->
                        val digits = value.text.filter { it.isDigit() }.take(6)
                        field = TextFieldValue(digits, TextRange(digits.length))
                        error = null
                    },
                    singleLine = true,
                    isError = error != null,
                    label = { Text("页码（1 – $pageCount）") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
                val err = error
                if (err != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = err,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onJump(0) }) { Text("第 1 页") }
                    if (pageCount > 2) {
                        TextButton(onClick = { onJump(pageCount / 2) }) { Text("中间") }
                    }
                    TextButton(onClick = { onJump(pageCount - 1) }) { Text("最后一页") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }) { Text("跳转") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 缩略图选页：滑一排小图，点哪页跳哪页。
 *
 * 用 LazyVerticalGrid 只渲染可见的格子，几百页也不会一次解码全部图片；
 * Coil 会按格子尺寸降采样解码，不会把 1600px 的原图塞进内存。
 * 打开时自动滚到当前页附近，当前页用橙色描边标出来。
 */
@Composable
private fun PagePicker(
    pages: List<String>,
    currentPage: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
    onTypePage: () -> Unit
) {
    // 打开时直接把当前页滚进视线（往上留两页上下文）。
    // 用 initialFirstVisibleItemIndex 而不是 LaunchedEffect：首帧还没完成布局时
    // scrollToItem 可能被取消，表现就是"每次打开都从第 1 页开始"。
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = (currentPage - 2).coerceAtLeast(0)
    )

    Surface(
        // 这一层铺满整个阅读器：命中测试会停在最上层，下面的翻页手势自然收不到
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0E0E12)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 8.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = Color.White)
                }
                Text(
                    text = "选择页面（共 ${pages.size} 页）",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onTypePage) {
                    Text("输入页码", color = MaterialTheme.colorScheme.primary)
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 84.dp),
                state = gridState,
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                // 必须用 weight(1f) 而不是 fillMaxSize()：在 Column 里 fillMaxSize 会让
                // 网格撑满整个父高度，导致它超出屏幕、最后一行点不到
                modifier = Modifier.weight(1f)
            ) {
                items(pages.size) { index ->
                    PageThumb(
                        path = pages[index],
                        index = index,
                        isCurrent = index == currentPage,
                        onClick = { onPick(index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PageThumb(
    path: String,
    index: Int,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.7f)
            .clip(shape)
            .background(Color(0xFF1A1A20))
            .then(
                if (isCurrent) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                }
            )
            .clickable { onClick() }
    ) {
        AsyncImage(
            model = File(path),
            contentDescription = "第 ${index + 1} 页",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
        Text(
            text = "${index + 1}",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(3.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xCC000000))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}