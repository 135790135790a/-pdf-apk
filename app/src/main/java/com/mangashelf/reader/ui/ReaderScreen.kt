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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
                Text(
                    text = "${pager.currentPage + 1} / $pageCount",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(end = 8.dp)
                )
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
                    TextButton(onClick = { viewModel.setRtl(!book.rtl) }) {
                        Text(
                            text = if (book.rtl) "改为从左往右" else "改为从右往左",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
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
