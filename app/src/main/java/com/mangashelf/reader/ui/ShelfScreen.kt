package com.mangashelf.reader.ui

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.mangashelf.reader.MangaShelfApp
import com.mangashelf.reader.data.Book
import com.mangashelf.reader.data.Diagnostics
import com.mangashelf.reader.data.PageSortMode
import com.mangashelf.reader.ui.theme.ComicScrim
import com.mangashelf.reader.ui.theme.ThemeMode
import java.io.File
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ShelfScreen(
    onOpenBook: (Long) -> Unit,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit
) {

    val context = LocalContext.current
    val app = context.applicationContext as MangaShelfApp
    val viewModel: ShelfViewModel = viewModel()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val order by viewModel.order.collectAsStateWithLifecycle()

    // 导入期间不让屏幕自动熄灭：锁屏是导入被系统挂起最常见的原因之一。
    // 真正切到后台时靠 ImportForegroundService 保命，两者配合。
    val importing = importState is ImportUiState.Running || importState is ImportUiState.Batch
    DisposableEffect(importing) {
        val window = (context as? Activity)?.window
        if (importing) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    var showImportDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showThemeMenu by remember { mutableStateOf(false) }
    var optionsBookId by remember { mutableStateOf<Long?>(null) }
    var pageOrderBookId by remember { mutableStateOf<Long?>(null) }
    var pendingZip by remember { mutableStateOf<File?>(null) }
    val safeMode = app.safeMode

    // 书架界面稳定显示 1.2 秒 → 本轮启动算健康。
    // 不能等"有书"才标记：新装用户书架是空的，否则会被启动守卫误判成连续启动失败而进安全模式。
    LaunchedEffect(Unit) {
        delay(1200)
        app.markShelfHealthy()
    }

    // 安全模式下导出诊断包：先在本应用私有目录生成，再让用户选保存位置
    val exportZip = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val zip = pendingZip
        if (uri != null && zip != null && zip.exists()) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    zip.inputStream().use { input -> input.copyTo(out) }
                }
            }.onSuccess {
                Toast.makeText(context, "诊断包已保存", Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(context, "保存失败：" + (it.message ?: "未知错误"), Toast.LENGTH_LONG).show()
            }
        }
        pendingZip = null
    }

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importPdf(uri)
    }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importImages(uris)
    }

    // 选一个文件夹 → 整个文件夹成一本书（递归子目录，按相对路径自然排序）
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.importFolder(uri)
        }
    }

    // 批量导入：选父文件夹，每个子文件夹 = 一本书
    val batchPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.importBatch(uri)
        }
    }

    val launchPdf = { pdfPicker.launch(arrayOf("application/pdf")) }
    val launchImages = { imagePicker.launch(arrayOf("image/*")) }
    val launchFolder = { folderPicker.launch(null) }
    val launchBatch = { batchPicker.launch(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "漫画书架",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                actions = {
                    Box {
                        TextButton(onClick = { showSortMenu = true }) {
                            Text("排序", color = MaterialTheme.colorScheme.onBackground)
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            ShelfSort.entries.forEach { sort ->
                                val selected = order.sort == sort
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = sort.label + if (selected) {
                                                if (order.asc) "  ↑ 正序" else "  ↓ 倒序"
                                            } else {
                                                ""
                                            },
                                            color = if (selected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            }
                                        )
                                    },
                                    leadingIcon = if (selected) {
                                        { Icon(Icons.Rounded.Check, contentDescription = null) }
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        viewModel.setSort(sort)
                                        showSortMenu = false
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("只切换正序 / 倒序") },
                                onClick = {
                                    viewModel.setSort(order.sort)
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                    // 主题切换：白天 / 夜晚 / 跟随系统
                    Box {
                        TextButton(onClick = { showThemeMenu = true }) {
                            Text("主题", color = MaterialTheme.colorScheme.onBackground)
                        }
                        DropdownMenu(
                            expanded = showThemeMenu,
                            onDismissRequest = { showThemeMenu = false }
                        ) {
                            ThemeMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label) },
                                    leadingIcon = if (themeMode == mode) {
                                        { Icon(Icons.Rounded.Check, contentDescription = null) }
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        onThemeChange(mode)
                                        showThemeMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showImportDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("导入漫画") }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (books.isEmpty()) {
                EmptyShelf(
                    onPickFolder = launchFolder,
                    onPickPdf = launchPdf,
                    onPickImages = launchImages
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    items(books, key = { it.id }) { book ->
                        BookCard(
                            book = book,
                            coverPath = viewModel.coverPath(book),
                            showCover = !safeMode,
                            onClick = { onOpenBook(book.id) },
                            onLongClick = { optionsBookId = book.id }
                        )
                    }
                }
            }

            if (safeMode) {
                SafeModeBanner(
                    modifier = Modifier.align(Alignment.TopCenter),
                    onExport = {
                        val zip = Diagnostics.export(context)
                        if (zip == null) {
                            Toast.makeText(context, "导出失败：磁盘空间不足或无写权限", Toast.LENGTH_LONG).show()
                        } else {
                            pendingZip = zip
                            exportZip.launch(zip.name)
                        }
                    }
                )
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("导入漫画") },
            text = {
                Column {
                    Text(
                        text = "选一个 PDF、一批图片，或者一整个文件夹（整个文件夹会按文件名顺序排成一本）。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            showImportDialog = false
                            launchFolder()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("选择文件夹（整本成一本书）")
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            showImportDialog = false
                            launchBatch()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("批量导入（父文件夹里的每一本）")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            showImportDialog = false
                            launchPdf()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("选择 PDF 文件")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            showImportDialog = false
                            launchImages()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("选择图片（可多选）")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("取消") }
            }
        )
    }

    when (val state = importState) {
        is ImportUiState.Running -> {
            AlertDialog(
                onDismissRequest = { },
                title = { Text("正在导入") },
                text = {
                    Column {
                        Text(state.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = if (state.total > 0) {
                                    "${state.done} / ${state.total} 页"
                                } else {
                                    "正在解析…"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = { }
            )
        }

        is ImportUiState.Batch -> {
            AlertDialog(
                onDismissRequest = { },
                title = {
                    Text(
                        when {
                            !state.paused -> "正在批量导入"
                            state.done < state.totalPages -> "正在暂停…"
                            else -> "已暂停"
                        }
                    )
                },
                text = {
                    Column {
                        Text(
                            text = "第 ${state.index} / ${state.total} 本",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(state.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(12.dp))
                        if (state.totalPages > 0) {
                            LinearProgressIndicator(
                                progress = { state.done.toFloat() / state.totalPages.coerceAtLeast(1) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "${state.done} / ${state.totalPages} 页",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = "正在解析…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (state.paused) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = if (state.done < state.totalPages) {
                                    "当前这本导完就停，已导入的书都在书架上。"
                                } else {
                                    "点「继续」接着导入。已导入的书都在书架上，不会丢。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (state.skipped > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "已跳过 ${state.skipped} 本（之前导过）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    Row {
                        TextButton(
                            onClick = {
                                if (state.paused) viewModel.resumeImport() else viewModel.pauseImport()
                            }
                        ) {
                            Text(if (state.paused) "继续" else "暂停")
                        }
                        TextButton(onClick = { viewModel.cancelImport() }) {
                            Text("取消", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }

        is ImportUiState.Finished -> {
            val parts = buildList {
                add("成功导入 ${state.imported} 本")
                if (state.skipped > 0) add("跳过 ${state.skipped} 本（已存在）")
                if (state.failed > 0) add("失败 ${state.failed} 本")
            }
            AlertDialog(
                onDismissRequest = { viewModel.dismissImportDialog() },
                title = { Text(if (state.cancelled) "已取消" else "批量导入完成") },
                text = { Text(parts.joinToString("，")) },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissImportDialog() }) { Text("好") }
                }
            )
        }

        is ImportUiState.Failed -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissImportDialog() },
                title = { Text("导入失败") },
                text = { Text(state.message) },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissImportDialog() }) { Text("知道了") }
                }
            )
        }

        ImportUiState.Idle -> Unit
    }

    val optionsBook = books.firstOrNull { it.id == optionsBookId }
    if (optionsBook != null) {
        BookOptionsDialog(
            book = optionsBook,
            onDismiss = { optionsBookId = null },
            onRename = { title -> viewModel.renameBook(optionsBook, title) },
            onToggleDirection = { viewModel.toggleDirection(optionsBook) },
            onOpenPageOrder = { pageOrderBookId = optionsBook.id },
            onDelete = {
                viewModel.deleteBook(optionsBook)
                optionsBookId = null
            }
        )
    }

    val pageOrderBook = books.firstOrNull { it.id == pageOrderBookId }
    if (pageOrderBook != null) {
        PageOrderDialog(
            book = pageOrderBook,
            onDismiss = { pageOrderBookId = null },
            onApply = { mode, asc ->
                viewModel.reorderPages(pageOrderBook, mode, asc)
                Toast.makeText(context, "已按「${mode.label}」重排 ${pageOrderBook.pageCount} 页", Toast.LENGTH_SHORT).show()
                pageOrderBookId = null
            }
        )
    }
}

@Composable
private fun EmptyShelf(
    onPickFolder: () -> Unit,
    onPickPdf: () -> Unit,
    onPickImages: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "📚", fontSize = 64.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "书架还是空的",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "把一个 PDF、一批图片，或者一整个文件夹导进来，\n就能像翻书一样看。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
            Text("导入文件夹")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onPickPdf, modifier = Modifier.fillMaxWidth()) {
            Text("导入 PDF")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onPickImages, modifier = Modifier.fillMaxWidth()) {
            Text("导入图片")
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "文件夹会按文件名顺序排成一本书（2.png → 10.png），\n子文件夹按名字顺序接在后面。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: Book,
    coverPath: String,
    showCover: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(
                model = if (showCover) File(coverPath) else null,
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                // 封面文件被清掉（比如用户清了 App 存储）时给个纯色块，别闪白也别崩
                error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
            )
            if (book.lastPage > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(ComicScrim)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "读到 ${book.lastPage + 1} 页",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = book.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "共 ${book.pageCount} 页 · ${if (book.rtl) "右→左" else "左→右"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(book.progressFraction)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun BookOptionsDialog(
    book: Book,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onToggleDirection: () -> Unit,
    onOpenPageOrder: () -> Unit,
    onDelete: () -> Unit
) {
    var title by remember(book.id) { mutableStateOf(book.title) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("漫画设置") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("书名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "当前阅读方向：${if (book.rtl) "从右往左（日漫）" else "从左往右（国漫/美漫）"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onToggleDirection) {
                    Text(if (book.rtl) "改为从左往右" else "改为从右往左")
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "页序：${PageSortMode.fromStorage(book.sortMode).label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onOpenPageOrder) {
                    Text("调整页面顺序")
                }
                TextButton(onClick = onDelete) {
                    Text("从书架删除", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newTitle = title.trim()
                    onRename(newTitle.ifBlank { book.title })
                    onDismiss()
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun PageOrderDialog(
    book: Book,
    onDismiss: () -> Unit,
    onApply: (PageSortMode, Boolean) -> Unit
) {
    val current = PageSortMode.fromStorage(book.sortMode)
    var mode by remember(book.id) { mutableStateOf(current) }
    var asc by remember(book.id) { mutableStateOf(book.sortAsc) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("页面顺序") },
        text = {
            Column {
                Text(
                    "这本书的 ${book.pageCount} 页按什么排？只改顺序，不重命名文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                PageSortMode.entries.forEach { m ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                mode = m
                                asc = m.defaultAsc
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = mode == m, onClick = {
                            mode = m
                            asc = m.defaultAsc
                        })
                        Column {
                            Text(m.label)
                            Text(
                                m.hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (asc) "正序（小 → 大）" else "倒序（大 → 小）")
                    Switch(
                        checked = asc,
                        onCheckedChange = { asc = it },
                        enabled = mode != PageSortMode.FIXED
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(mode, asc) }) { Text("应用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun SafeModeBanner(modifier: Modifier = Modifier, onExport: () -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "安全模式：封面已关闭、图片缓存已清理",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onExport) {
            Text("导出诊断包", color = MaterialTheme.colorScheme.primary)
        }
    }
}