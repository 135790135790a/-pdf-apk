package com.mangashelf.reader.ui

import android.app.Activity
import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.mangashelf.reader.MangaShelfApp
import com.mangashelf.reader.data.Book
import com.mangashelf.reader.data.GalleryExporter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(bookId: Long, onBack: () -> Unit, onManagePages: () -> Unit = {}) {
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
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    } else {
        ReaderContent(viewModel = viewModel, book = currentBook, onBack = onBack, onManagePages = onManagePages)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReaderContent(
    viewModel: ReaderViewModel,
    book: Book,
    onBack: () -> Unit,
    onManagePages: () -> Unit
) {
    // 页序存在数据库里（pages 表），要异步读；null = 还在加载
    val pages by produceState<List<String>?>(initialValue = null, book.id, book.pageCount) {
        value = viewModel.loadPages(book)
    }

    if (pages == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    val pageList = pages.orEmpty()
    if (pageList.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Text("这本书没有可显示的页面", color = MaterialTheme.colorScheme.onBackground)
        }
        return
    }

    val pageCount = pageList.size
    // 双页模式：一个"跨页"放两张图，所以 Pager 的页数要减半（奇数页最后一张单独一屏）。
    // 至少要有 2 页才值得开双页，只有 1 页时自动当单页处理。
    val spread = book.twoPage && pageCount > 1
    val slotCount = if (spread) (pageCount + 1) / 2 else pageCount
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(
        initialPage = ((if (spread) book.lastPage / 2 else book.lastPage))
            .coerceIn(0, slotCount - 1),
        pageCount = { slotCount }
    )
    // 当前屏对应的第一页（0 基）。书签、保存、跳页都按真实页码走，不按跨页序号。
    fun firstPageOf(slot: Int): Int = (if (spread) slot * 2 else slot).coerceIn(0, pageCount - 1)
    var chromeVisible by remember { mutableStateOf(true) }
    var zoomedIn by remember { mutableStateOf(false) }
    var showJumpDialog by remember { mutableStateOf(false) }
    var showPagePicker by remember { mutableStateOf(false) }

    ImmersiveMode()

    LaunchedEffect(pager.currentPage, spread) {
        zoomedIn = false
        viewModel.saveProgress(firstPageOf(pager.currentPage))
    }

    BackHandler {
        if (!chromeVisible) chromeVisible = true else onBack()
    }

    val imageContext = LocalContext.current
    val haptics = LocalHapticFeedback.current

    // 翻页相关的偏好。改了对话框里的选项就地更新，不用退出去重进
    var sensitivity by remember { mutableStateOf(PagePrefs.sensitivity(imageContext)) }
    var volumeKeys by remember { mutableStateOf(PagePrefs.volumeKeys(imageContext)) }
    var longPressTurn by remember { mutableStateOf(PagePrefs.longPressTurn(imageContext)) }
    var showTurnSettings by remember { mutableStateOf(false) }

    // 长按页面 → 保存这一页到相册。漫画里常有想单独存下来的整页，
    // 复制到相册比"截图再裁剪"省事；单页失败不影响阅读，只弹个提示。
    var savingPage by remember { mutableStateOf(false) }
    val doSavePage: () -> Unit = {
        val pageIndex = firstPageOf(pager.currentPage)
        val path = pageList.getOrNull(pageIndex).orEmpty()
        if (path.isNotEmpty() && !savingPage) {
            val pageNumber = pageIndex + 1
            savingPage = true
            scope.launch {
                val saved = GalleryExporter.export(
                    imageContext,
                    "${book.title}_第${pageNumber}页",
                    listOf(File(path))
                )
                Toast.makeText(
                    imageContext,
                    if (saved > 0) {
                        "已保存第 $pageNumber 页到相册"
                    } else {
                        "保存失败：相册写入被拒绝（可能是存储权限或空间不足）"
                    },
                    Toast.LENGTH_SHORT
                ).show()
                savingPage = false
            }
        }
    }

    // Android 9 及以下写相册需要存储权限；10+ 走 MediaStore，不弹权限
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            doSavePage()
        } else {
            Toast.makeText(imageContext, "没有存储权限，无法保存图片", Toast.LENGTH_SHORT).show()
        }
    }

    val onLongPressPage: () -> Unit = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val needPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                imageContext,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        if (needPermission) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            doSavePage()
        }
    }

    val goTo: (Int) -> Unit = { target ->
        // 传进来的是真实页码；双页模式下要换算成跨页序号
        val slot = if (spread) target / 2 else target
        val clamped = slot.coerceIn(0, slotCount - 1)
        if (clamped != pager.currentPage) {
            scope.launch {
                pager.animateScrollToPage(clamped, animationSpec = tween(sensitivity.animMs))
            }
        } else if (slot != pager.currentPage) {
            // 本来就在第一页/最后一页：给个提示，不能拖了半天一点反应都没有
            Toast.makeText(
                imageContext,
                if (slot < 0) "已经是第一页" else "已经是最后一页",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // 按跨页序号翻页（缩放态边缘拖拽用，不经过页码换算）
    val turnBy: (Int) -> Unit = { delta ->
        val target = pager.currentPage + delta
        if (target in 0 until slotCount) {
            scope.launch {
                pager.animateScrollToPage(target, animationSpec = tween(sensitivity.animMs))
            }
        } else {
            Toast.makeText(
                imageContext,
                if (target < 0) "已经是第一页" else "已经是最后一页",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // 音量键：只有开了开关才挂回调，退出阅读器（或关掉开关）立刻摘掉
    val currentTurnBy by rememberUpdatedState(turnBy)
    DisposableEffect(volumeKeys) {
        if (volumeKeys) {
            PageKeyBus.onTurn = { forward -> currentTurnBy(if (forward) 1 else -1) }
        }
        onDispose { PageKeyBus.onTurn = null }
    }

    // 长按连翻：按住页面不放就一直往后翻，松手停。
    // 只在用户明确打开开关时生效——默认关，避免和"长按存图"抢同一个手势。
    var autoTurning by remember { mutableStateOf(false) }
    LaunchedEffect(autoTurning) {
        while (autoTurning) {
            currentTurnBy(1)
            delay(420L)
        }
    }

    // 预取前后各一屏：翻页时图片已经在内存/磁盘缓存里，不会先白一下。
    // 用 Coil 主动 enqueue，而不是让 Pager 多组合一页——多组合会同时解码大图，更容易 OOM。
    LaunchedEffect(pager.currentPage, slotCount, spread) {
        listOf(pager.currentPage + 1, pager.currentPage - 1).forEach { slot ->
            val start = if (spread) slot * 2 else slot
            val indexes = if (spread) listOf(start, start + 1) else listOf(start)
            indexes.forEach { index ->
                val path = pageList.getOrNull(index).orEmpty()
                if (path.isNotEmpty()) {
                    imageContext.imageLoader.enqueue(
                        ImageRequest.Builder(imageContext).data(File(path)).build()
                    )
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            reverseLayout = book.rtl,
            userScrollEnabled = !zoomedIn
        ) { index ->
            val slotStart = if (spread) index * 2 else index
            val firstPath = pageList.getOrNull(slotStart).orEmpty()
            val secondPath = if (spread) pageList.getOrNull(slotStart + 1) else null
            ZoomablePage(
                // 一本书的页数可能在别处被导入/取消改动，索引越界时给空路径让 Coil 走错误占位，
                // 直接 pages[index] 会抛 IndexOutOfBounds 把整个阅读器带崩。
                // 日漫从右往左读，所以"后一页"在左边、"前一页"在右边。
                path = if (book.rtl) secondPath.orEmpty() else firstPath,
                secondPath = if (book.rtl) firstPath else secondPath,
                isCurrent = index == pager.currentPage,
                sensitivity = sensitivity,
                onEdgeFling = { dragLeft ->
                    // 和未缩放时滑动方向保持一致，否则日漫模式下会反着翻
                    val forward = if (book.rtl) !dragLeft else dragLeft
                    turnBy(if (forward) 1 else -1)
                },
                onZoomChanged = { zoomed -> if (index == pager.currentPage) zoomedIn = zoomed },
                onLongPress = onLongPressPage,
                longPressTurn = longPressTurn,
                onAutoTurn = { autoTurning = it },
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
                    .background(
                        Brush.verticalGradient(
                            listOf(MaterialTheme.colorScheme.background.copy(alpha = 0.88f), Color.Transparent)
                        )
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = "返回书架",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Text(
                    text = book.title,
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // 页码做成可点的按钮：点它就能输入页码直接跳过去
                TextButton(onClick = onLongPressPage) {
                    Text(
                        text = "保存本页",
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.14f))
                        .clickable { showPagePicker = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    val shownStart = firstPageOf(pager.currentPage) + 1
                    val shownEnd = if (spread) (shownStart + 1).coerceAtMost(pageCount) else shownStart
                    Text(
                        text = if (shownEnd > shownStart) {
                            "$shownStart-$shownEnd / $pageCount"
                        } else {
                            "$shownStart / $pageCount"
                        },
                        color = MaterialTheme.colorScheme.onBackground,
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
                    .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background.copy(alpha = 0.88f))))
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                if (slotCount > 1) {
                    Slider(
                        value = pager.currentPage.toFloat(),
                        onValueChange = { value ->
                            val target = value.roundToInt().coerceIn(0, slotCount - 1)
                            scope.launch { pager.scrollToPage(target) }
                        },
                        valueRange = 0f..(slotCount - 1).toFloat()
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (book.rtl) "阅读方向：从右往左" else "阅读方向：从左往右",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showPagePicker = true }) {
                        Text("跳页", color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = onManagePages) {
                        Text(text = "页面管理", fontSize = 12.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { viewModel.setRtl(!book.rtl) }) {
                        Text(
                            text = if (book.rtl) "改为从左往右" else "改为从右往左",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp
                        )
                    }
                    if (pageCount > 1) {
                        TextButton(onClick = { viewModel.setTwoPage(!book.twoPage) }) {
                            Text(
                                text = if (book.twoPage) "改单页" else "改双页",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp
                            )
                        }
                    }
                    TextButton(onClick = { showTurnSettings = true }) {
                        Text(
                            text = "翻页设置",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
        if (showTurnSettings) {
            PageTurnSettingsDialog(
                sensitivity = sensitivity,
                volumeKeys = volumeKeys,
                longPressTurn = longPressTurn,
                onSensitivity = {
                    sensitivity = it
                    PagePrefs.setSensitivity(imageContext, it)
                },
                onVolumeKeys = {
                    volumeKeys = it
                    PagePrefs.setVolumeKeys(imageContext, it)
                },
                onLongPressTurn = {
                    longPressTurn = it
                    PagePrefs.setLongPressTurn(imageContext, it)
                },
                onDismiss = { showTurnSettings = false }
            )
        }
        if (showPagePicker) {
            PagePicker(
                pages = pageList,
                currentPage = firstPageOf(pager.currentPage),
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
            currentPage = firstPageOf(pager.currentPage),
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
    secondPath: String?,
    isCurrent: Boolean,
    sensitivity: PageSensitivity,
    onZoomChanged: (Boolean) -> Unit,
    onTapAt: (Float) -> Unit,
    onLongPress: () -> Unit,
    /** 长按连翻开关：开了就由「按住不放」驱动连续翻页，长按存图改由按钮触发 */
    longPressTurn: Boolean,
    onAutoTurn: (Boolean) -> Unit,
    /** 放大后拖到边界还想继续拖、并且拖够了/甩得够快：dragLeft = 往左越界 */
    onEdgeFling: (dragLeft: Boolean) -> Unit
) {
    var scale by remember(path, secondPath) { mutableStateOf(1f) }
    var offset by remember(path, secondPath) { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // pointerInput 的 lambda 只在 path 变化时重建，直接用外部 lambda 会捕获旧值；
    // 用 rememberUpdatedState 保证长按/点击永远调用到最新的一版（指向当前页）。
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnTapAt by rememberUpdatedState(onTapAt)
    val currentOnEdgeFling by rememberUpdatedState(onEdgeFling)
    val currentOnAutoTurn by rememberUpdatedState(onAutoTurn)
    val currentScale = rememberUpdatedState(scale)

    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    LaunchedEffect(scale) {
        onZoomChanged(scale > 1.01f)
    }

    // 双页模式下两张图并排，整排一起缩放/平移：对开本漫画的跨页大图要一起看才连续
    val left = path
    val right = secondPath.orEmpty()
    val twoUp = left.isNotEmpty() && right.isNotEmpty()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewSize = it }
            .pointerInput(path, secondPath, sensitivity) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // 一只手势里累计"拖到边界外还想继续拖"的量。
                    // 缩放态下这个量超过阈值（或甩得够快）就翻页——这是"放大后出不来"的解药。
                    var overdrag = 0f
                    var velocity = 0f
                    var lastTime = 0L
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) {
                            if (overdrag != 0f) {
                                val threshold = size.width * sensitivity.edgeFraction
                                // 拖动量超过了阈值、或者甩得够快 → 翻页；否则什么都不做，图自然停在边界
                                if (abs(overdrag) >= threshold || abs(velocity) >= sensitivity.flingVelocity) {
                                    currentOnEdgeFling(overdrag < 0f)
                                }
                                overdrag = 0f
                            }
                            break
                        }
                        if (pressed > 1) {
                            // 双指：只缩放，不累计超拖
                            val zoom = event.calculateZoom()
                            val next = (scale * zoom).coerceIn(1f, 4f)
                            scale = next
                            overdrag = 0f
                            velocity = 0f
                            event.changes.forEach { it.consume() }
                        } else if (scale > 1.01f) {
                            // 单指 + 已放大 = 平移；越过边界的那部分记进 overdrag
                            val pan = event.calculatePan()
                            val maxX = (size.width * (scale - 1f)) / 2f
                            val desiredX = offset.x + pan.x
                            val clampedX = desiredX.coerceIn(-maxX, maxX)
                            overdrag += desiredX - clampedX
                            val now = SystemClock.uptimeMillis()
                            if (lastTime != 0L && pan.x != 0f) {
                                val dt = (now - lastTime).coerceAtLeast(1L).toFloat()
                                velocity = pan.x / dt * 1000f
                            }
                            lastTime = now
                            offset = clampOffset(
                                Offset(clampedX, offset.y + pan.y),
                                viewSize,
                                scale
                            )
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .pointerInput(path, secondPath, longPressTurn) {
                detectTapGestures(
                    // 长按连翻：按下后 480ms 还没松手 = 开始连翻，松手即停。
                    // 用 onPress 而不是 onLongPress——只有 onPress 能感知"什么时候松手"。
                    onPress = {
                        if (longPressTurn && currentScale.value <= 1.01f) {
                            val released = withTimeoutOrNull(480L) { tryAwaitRelease() }
                            if (released == null) {
                                currentOnAutoTurn(true)
                                tryAwaitRelease()
                                currentOnAutoTurn(false)
                            }
                        }
                    },
                    onTap = { position ->
                        // 放大状态下点击不翻页：这时用户在看细节，点一下就跳页太容易误触。
                        // 想翻页先双击复位（或拖到边界继续拖）。
                        if (scale <= 1.01f) {
                            val width = if (viewSize.width == 0) 1 else viewSize.width
                            currentOnTapAt((position.x / width.toFloat()).coerceIn(0f, 0.999f))
                        }
                    },
                    // 长按 = 保存这一页（缩放状态下也有效，方便保存放大后的区域观察）。
                    // 开了「长按连翻」就以连翻为准，存图走工具栏的「保存本页」按钮。
                    onLongPress = { if (!longPressTurn) currentOnLongPress() },
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
        val layer = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
        }
        if (twoUp) {
            Row(modifier = Modifier.fillMaxSize().then(layer)) {
                PageImage(left, Modifier.weight(1f).fillMaxHeight())
                PageImage(right, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            // 奇数页的最后一屏只有一张图：让它单独占满，不留半屏空白
            PageImage(if (left.isNotEmpty()) left else right, Modifier.fillMaxSize().then(layer))
        }
    }
}

/** 单张页面图；解码失败时给占位文案，不抛异常、不白屏 */
@Composable
private fun PageImage(path: String, modifier: Modifier) {
    SubcomposeAsyncImage(
        model = File(path),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier,
        // 文件被删 / 格式坏 / 解码失败：显示占位，不抛异常、不白屏
        error = {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "这一页无法显示\n文件可能已被删除或损坏",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
    )
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
        color = MaterialTheme.colorScheme.background
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
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Text(
                    text = "选择页面（共 ${pages.size} 页）",
                    color = MaterialTheme.colorScheme.onBackground,
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
            .background(MaterialTheme.colorScheme.surface)
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
            modifier = Modifier.fillMaxSize(),
            // 缩略图读不出来就留一个深灰块，不要让网格里出现异常
            error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
        )
        Text(
            text = "${index + 1}",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(3.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.82f))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

/**
 * 翻页设置。
 *
 * 灵敏度用三档而不是滑杆：滑杆的数字对用户没意义，三档能直接说出差别
 * （"不易误翻 / 默认 / 很灵敏"），也不需要在真机上反复试参数。
 */
@Composable
private fun PageTurnSettingsDialog(
    sensitivity: PageSensitivity,
    volumeKeys: Boolean,
    longPressTurn: Boolean,
    onSensitivity: (PageSensitivity) -> Unit,
    onVolumeKeys: (Boolean) -> Unit,
    onLongPressTurn: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("翻页设置") },
        text = {
            Column {
                Text(
                    text = "灵敏度",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PageSensitivity.entries.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSensitivity(option) }
                            .padding(vertical = 2.dp)
                    ) {
                        RadioButton(
                            selected = option == sensitivity,
                            onClick = { onSensitivity(option) }
                        )
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("音量键翻页", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "下键下一页、上键上一页",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = volumeKeys, onCheckedChange = onVolumeKeys)
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("长按连翻", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "按住页面不放连续往后翻，松手停；开启后长按不再存图",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = longPressTurn, onCheckedChange = onLongPressTurn)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (longPressTurn) {
                        "长按 = 连续翻页；保存图片用工具栏的「保存本页」。双击 = 放大 / 复位；放大后拖到边缘继续拖 = 翻页。"
                    } else {
                        "长按图片 = 保存这一页；双击 = 放大 / 复位；放大后拖到边缘继续拖 = 翻页。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}