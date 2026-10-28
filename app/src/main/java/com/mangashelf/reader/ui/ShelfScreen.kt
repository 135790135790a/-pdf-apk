package com.mangashelf.reader.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.mangashelf.reader.MangaShelfApp
import com.mangashelf.reader.data.Book
import com.mangashelf.reader.data.CoverQuality
import com.mangashelf.reader.data.Diagnostics
import com.mangashelf.reader.data.ImportSortPrefs
import com.mangashelf.reader.data.PageSortMode
import com.mangashelf.reader.ui.theme.ComicScrim
import com.mangashelf.reader.ui.theme.ThemeMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ShelfScreen(
    onOpenBook: (Long) -> Unit,
    onManagePages: (Long) -> Unit,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit
) {

    val context = LocalContext.current
    val app = context.applicationContext as MangaShelfApp
    val viewModel: ShelfViewModel = viewModel()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val order by viewModel.order.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val interrupted by viewModel.interrupted.collectAsStateWithLifecycle()
    val covers by viewModel.covers.collectAsStateWithLifecycle()
    val coverQuality by viewModel.coverQuality.collectAsStateWithLifecycle()
    val continueBook by viewModel.continueBook.collectAsStateWithLifecycle()

    // 导入期间不让屏幕自动熄灭：锁屏是导入被系统挂起最常见的原因之一。
    // 真正切到后台时靠 ImportForegroundService 保命，两者配合。
    val importing = importState is ImportUiState.Running || importState is ImportUiState.Batch
    DisposableEffect(importing) {
        val window = (context as? Activity)?.window
        if (importing) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    var showImportDialog by remember { mutableStateOf(false) }
    // 导入前的排序选择（图片/文件夹两种来源各自记默认值）
    var pendingSort by remember { mutableStateOf<PendingImport?>(null) }
    // 长按「保存到相册」要导整本还是只导封面
    var saveChoiceBookId by remember { mutableStateOf<Long?>(null) }
    // Android 9 及以下写相册要先要存储权限；等授权时把待保存的参数缓存起来
    var pendingSave by remember { mutableStateOf<Pair<Book, Boolean>?>(null) }
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    // 搜索：点放大镜展开输入框，再点关闭收起。收起时清空关键词，书架恢复完整。
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    var showSortMenu by remember { mutableStateOf(false) }
    var showLayoutMenu by remember { mutableStateOf(false) }
    // 书架布局：切换后立刻生效并记住，下次打开 App 还是这个视图
    var layout by remember { mutableStateOf(ShelfLayoutPrefs.load(context)) }
    var showThemeMenu by remember { mutableStateOf(false) }
    var showImportSortSettings by remember { mutableStateOf(false) }
    var showBackupMenu by remember { mutableStateOf(false) }
    var optionsBookId by remember { mutableStateOf<Long?>(null) }
    var pageOrderBookId by remember { mutableStateOf<Long?>(null) }
    var pendingZip by remember { mutableStateOf<File?>(null) }
    val safeMode = app.safeMode

    // 展开搜索时自动聚焦输入框；收起时清空关键词（书架立刻恢复完整）
    LaunchedEffect(searchActive) {
        if (searchActive) {
            runCatching { searchFocus.requestFocus() }
        } else {
            searchText = ""
            viewModel.setQuery("")
        }
    }

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

    // CBZ / ZIP 漫画包：压缩包里每张图 = 一页。不同文件管理器给 .cbz 报的 MIME 不一样，都列上
    val archivePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importArchive(uri)
    }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) pendingSort = PendingImport.Images(uris)
    }

    // 选一个文件夹 → 整个文件夹成一本书（排序规则在弹窗里选，默认取上次的设置）
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
            pendingSort = PendingImport.Folder(uri)
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
    val launchArchive = {
        archivePicker.launch(
            arrayOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/x-cbz",
                "application/vnd.comicbook+zip",
                "application/octet-stream"
            )
        )
    }

    // 备份 = 一个 zip（含图片）。存到用户选的位置，不依赖账号或服务器
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()
    val backupCreate = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) viewModel.exportBackup(uri)
    }
    val backupOpen = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.restoreBackup(uri)
    }

    // 保存到相册：10+ 直接写；9- 先申请 WRITE_EXTERNAL_STORAGE
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingSave
        pendingSave = null
        if (pending == null) return@rememberLauncherForActivityResult
        if (granted) {
            viewModel.saveToGallery(pending.first, pending.second)
        } else {
            Toast.makeText(
                context,
                "没有存储权限，写不进相册。可以在系统设置里给本应用开启后重试。",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    val startSave: (Book, Boolean) -> Unit = { book, allPages ->
        val needPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        if (needPermission) {
            pendingSave = book to allPages
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            viewModel.saveToGallery(book, allPages)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    if (searchActive) {
                        OutlinedTextField(
                            value = searchText,
                            onValueChange = {
                                searchText = it
                                viewModel.setQuery(it)
                            },
                            placeholder = { Text("搜索书名 / 作者 / 标签 / 文件夹") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 4.dp)
                                .focusRequester(searchFocus)
                        )
                    } else {
                        Text(
                            text = "漫画书架",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                actions = {
                    IconButton(onClick = { searchActive = !searchActive }) {
                        Icon(
                            imageVector = if (searchActive) {
                                Icons.Rounded.Close
                            } else {
                                Icons.Rounded.Search
                            },
                            contentDescription = if (searchActive) "关闭搜索" else "搜索"
                        )
                    }
                    Box {
                        TextButton(onClick = { showLayoutMenu = true }) {
                            Text(layout.label, color = MaterialTheme.colorScheme.onBackground)
                        }
                        DropdownMenu(
                            expanded = showLayoutMenu,
                            onDismissRequest = { showLayoutMenu = false }
                        ) {
                            ShelfLayout.entries.forEach { option ->
                                val selected = option == layout
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = option.label,
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
                                        layout = option
                                        ShelfLayoutPrefs.save(context, option)
                                        showLayoutMenu = false
                                    }
                                )
                            }
                            HorizontalDivider()
                            CoverQuality.entries.forEach { quality ->
                                val selected = quality == coverQuality
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "封面：" + quality.label,
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
                                        viewModel.setCoverQuality(quality)
                                        showLayoutMenu = false
                                    }
                                )
                            }
                        }
                    }
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
                    // 导入默认排序（文件夹 / 图片分开设置）
                    TextButton(onClick = { showImportSortSettings = true }) {
                        Text("导入排序", color = MaterialTheme.colorScheme.onBackground)
                    }
                    // 整库备份 / 恢复（含图片）
                    Box {
                        TextButton(onClick = { showBackupMenu = true }) {
                            Text("备份", color = MaterialTheme.colorScheme.onBackground)
                        }
                        DropdownMenu(
                            expanded = showBackupMenu,
                            onDismissRequest = { showBackupMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("导出备份（含图片）") },
                                onClick = {
                                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
                                        .format(Date())
                                    backupCreate.launch("漫画书架备份-$stamp.zip")
                                    showBackupMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("从备份恢复") },
                                onClick = {
                                    backupOpen.launch(arrayOf("*/*"))
                                    showBackupMenu = false
                                }
                            )
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
                if (query.isNotBlank()) {
                    // 有搜索词但没结果：不能显示「书架空空」的导入引导，那会误导成书丢了
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "没有匹配「${query.trim()}」的书",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "换个关键词，或点右上角 × 关闭搜索",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    EmptyShelf(
                        onPickFolder = launchFolder,
                        onPickPdf = launchPdf,
                        onPickImages = launchImages
                    )
                }
            } else {
                when (layout) {
                    ShelfLayout.GRID -> LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        continueBook?.let { recent ->
                            item(span = { GridItemSpan(maxLineSpan) }, key = "continue-reading") {
                                ContinueReadingCard(
                                    book = recent,
                                    coverPath = covers[recent.id].orEmpty(),
                                    onClick = { onOpenBook(recent.id) }
                                )
                            }
                        }
                        items(books, key = { it.id }) { book ->
                            BookCard(
                                book = book,
                                coverPath = covers[book.id].orEmpty(),
                                showCover = !safeMode,
                                onClick = { onOpenBook(book.id) },
                                onLongClick = { optionsBookId = book.id }
                            )
                        }
                    }

                    ShelfLayout.LIST -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        continueBook?.let { recent ->
                            item(key = "continue-reading") {
                                ContinueReadingCard(
                                    book = recent,
                                    coverPath = covers[recent.id].orEmpty(),
                                    onClick = { onOpenBook(recent.id) }
                                )
                            }
                        }
                        items(books, key = { it.id }) { book ->
                            BookRow(
                                book = book,
                                coverPath = covers[book.id].orEmpty(),
                                showCover = !safeMode,
                                onClick = { onOpenBook(book.id) },
                                onLongClick = { optionsBookId = book.id }
                            )
                        }
                    }

                    ShelfLayout.STAGGERED -> LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalItemSpacing = 18.dp
                    ) {
                        continueBook?.let { recent ->
                            item(span = StaggeredGridItemSpan.FullLine, key = "continue-reading") {
                                ContinueReadingCard(
                                    book = recent,
                                    coverPath = covers[recent.id].orEmpty(),
                                    onClick = { onOpenBook(recent.id) }
                                )
                            }
                        }
                        items(books, key = { it.id }) { book ->
                            BookCard(
                                book = book,
                                coverPath = covers[book.id].orEmpty(),
                                showCover = !safeMode,
                                // 0.60 ~ 0.78 之间按书轮换，相邻两列自然错开
                                coverRatio = 0.60f + (book.id % 4).toFloat() * 0.06f,
                                onClick = { onOpenBook(book.id) },
                                onLongClick = { optionsBookId = book.id }
                            )
                        }
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

            // 上次导入被系统打断（进程被回收/用户强杀）：说清楚差几本，并给重试入口。
            // 只在没有导入在跑的时候显示，免得和进行中的进度条打架。
            interrupted?.let { info ->
                if (!importing) {
                    InterruptedBanner(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(start = 16.dp, end = 16.dp, bottom = 96.dp),
                        info = info,
                        onRetry = { viewModel.retryInterrupted() },
                        onDismiss = { viewModel.dismissInterrupted() }
                    )
                }
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
                        text = "选一个 PDF、一批图片、一整个文件夹，或者一个 CBZ / ZIP 漫画包（整个文件夹和压缩包都会按文件名顺序排成一本）。",
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
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            showImportDialog = false
                            launchArchive()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("选择 CBZ / ZIP 压缩包")
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
                                    // v1.5.5：PDF/文件夹逐页渲染，明说"第几页"，避免长任务被误当成卡死
                                    "正在渲染第 ${state.done} / ${state.total} 页"
                                } else {
                                    "正在解析…"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (state.total > 0) {
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { state.done.toFloat() / state.total.coerceAtLeast(1) },
                                modifier = Modifier.fillMaxWidth()
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
                    Row {
                        // 有失败项才给重试入口，否则"重试"按钮点了也没事发生
                        if (state.failed > 0 && state.batchId != null) {
                            TextButton(onClick = { viewModel.retryFailedOf(state) }) {
                                Text("重试失败项")
                            }
                        }
                        TextButton(onClick = { viewModel.dismissImportDialog() }) { Text("好") }
                    }
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
            onSaveMeta = { author, tags -> viewModel.updateBookMeta(optionsBook, author, tags) },
            onToggleDirection = { viewModel.toggleDirection(optionsBook) },
            onSetReadMode = { mode -> viewModel.setReadMode(optionsBook, mode) },
            onManagePages = {
                optionsBookId = null
                onManagePages(optionsBook.id)
            },
            onSaveToGallery = {
                saveChoiceBookId = optionsBook.id
                optionsBookId = null
            },
            onDelete = {
                viewModel.deleteBook(optionsBook)
                optionsBookId = null
            }
        )
    }

    // 保存到相册：先问范围，避免手滑把几百页塞进相册
    val saveBook = books.firstOrNull { it.id == saveChoiceBookId }
    if (saveBook != null) {
        AlertDialog(
            onDismissRequest = { saveChoiceBookId = null },
            title = { Text("保存到相册") },
            text = {
                Text("《${saveBook.title}》共 ${saveBook.pageCount} 页。\n导出到 Pictures/MangaShelf，相册里能直接看。")
            },
            confirmButton = {
                TextButton(onClick = {
                    startSave(saveBook, true)
                    saveChoiceBookId = null
                }) { Text("全部 ${saveBook.pageCount} 页") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { saveChoiceBookId = null }) { Text("取消") }
                    TextButton(onClick = {
                        startSave(saveBook, false)
                        saveChoiceBookId = null
                    }) { Text("只存封面") }
                }
            }
        )
    }

    // 导出进度：不可取消，但显示到第几张，避免用户以为卡死
    val saving = saveState
    if (saving != null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("正在保存到相册") },
            text = {
                Column {
                    Text("《${saving.title}》 ${saving.done}/${saving.total}")
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (saving.total == 0) 0f else saving.done.toFloat() / saving.total
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = { }
        )
    }

    // 备份 / 恢复进度
    val backingUp = backupState
    if (backingUp != null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(backingUp.label) },
            text = {
                Column {
                    Text(
                        if (backingUp.total > 0) {
                            "${backingUp.done}/${backingUp.total}"
                        } else {
                            "正在处理，备份包含图片，书多时会慢一些…"
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (backingUp.total == 0) 0f else backingUp.done.toFloat() / backingUp.total
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = { }
        )
    }

    // Toast 型提示统一从这里出
    LaunchedEffect(message) {        val text = message ?: return@LaunchedEffect
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        viewModel.clearMessage()
    }

    // 导入前的排序选择
    val pending = pendingSort
    if (pending != null) {
        val isFolder = pending is PendingImport.Folder
        ImportSortDialog(
            isFolder = isFolder,
            initialMode = if (isFolder) {
                ImportSortPrefs.folderMode(context)
            } else {
                ImportSortPrefs.imageMode(context)
            },
            initialAsc = if (isFolder) {
                ImportSortPrefs.folderAsc(context)
            } else {
                ImportSortPrefs.imageAsc(context)
            },
            onConfirm = { mode, asc ->
                when (pending) {
                    is PendingImport.Images -> viewModel.importImages(pending.uris, mode, asc)
                    is PendingImport.Folder -> viewModel.importFolder(pending.uri, mode, asc)
                }
                pendingSort = null
            },
            onDismiss = { pendingSort = null }
        )
    }

    if (showImportSortSettings) {
        ImportSortSettingsDialog(onDismiss = { showImportSortSettings = false })
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

/**
 * 书架顶部的「继续阅读」卡片：接着上次那一本、那一页往下看。
 *
 * 只在"最近读过且没读完"时出现；搜索时不出现（那时用户在找别的书）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueReadingCard(
    book: Book,
    coverPath: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(54.dp)
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(
                model = if (coverPath.isNotEmpty()) File(coverPath) else null,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "继续阅读",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "第 ${book.lastPage + 1} / ${book.pageCount} 页",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = book.progressFraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: Book,
    coverPath: String,
    showCover: Boolean,
    /** 封面宽高比。瀑布流里按书取不同比例，让相邻两列错落开 */
    coverRatio: Float = 0.72f,
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
                .aspectRatio(coverRatio)
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

private fun formatBookDate(at: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(at))

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(
    book: Book,
    coverPath: String,
    showCover: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(56.dp)
                .height(78.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(
                model = if (showCover) File(coverPath) else null,
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    append("${book.pageCount} 页")
                    if (book.lastPage > 0) append(" · 读到第 ${book.lastPage + 1} 页")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (book.pageCount > 1 && book.lastPage > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { book.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

@Composable
private fun BookOptionsDialog(
    book: Book,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onSaveMeta: (String, String) -> Unit,
    onToggleDirection: () -> Unit,
    onSetReadMode: (String) -> Unit,
    onManagePages: () -> Unit,
    onSaveToGallery: () -> Unit,
    onDelete: () -> Unit
) {
    var title by remember(book.id) { mutableStateOf(book.title) }
    var author by remember(book.id) { mutableStateOf(book.author) }
    var tags by remember(book.id) { mutableStateOf(book.tags) }
    var confirmingDelete by remember(book.id) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("漫画设置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("书名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                // v1.5.5：作者 / 标签，书架顶部搜索会一起匹配
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("作者（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("标签（可选）") },
                    singleLine = true,
                    supportingText = { Text("多个标签用逗号或空格分隔，搜索时按包含匹配") },
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
                Spacer(Modifier.height(10.dp))
                // v1.5.5 需求三：阅读模式三选一（按书保存）
                Text(
                    text = "阅读模式",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "HORIZONTAL" to "左右翻页",
                        "VERTICAL" to "上下滚动",
                        "DOUBLE" to "双页并排"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = book.readMode == mode,
                            onClick = { onSetReadMode(mode) },
                            label = {
                                Text(label, style = MaterialTheme.typography.labelMedium)
                            }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = "${book.pageCount} 页 · ${book.sourceType} · 导入于 ${formatBookDate(book.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "页序：${PageSortMode.fromStorage(book.sortMode).label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onManagePages) {
                    Text("页面管理（排序 / 删除 / 封面）")
                }
                TextButton(onClick = onSaveToGallery) {
                    Text("保存到相册")
                }
                TextButton(onClick = {
                    // 二次确认：第一次点只是变成「确认删除」，避免手滑
                    if (!confirmingDelete) confirmingDelete = true else onDelete()
                }) {
                    Text(
                        text = if (confirmingDelete) "再点一次确认删除（图片一起删）" else "从书架删除",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newTitle = title.trim()
                    onRename(newTitle.ifBlank { book.title })
                    onSaveMeta(author.trim(), tags.trim())
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
private fun SafeModeBanner(modifier: Modifier = Modifier, onExport: () -> Unit) {    Row(
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

/**
 * 上次导入被打断后的提示条。
 *
 * 只陈述事实（差几本、哪几本）并给两个出口，不做自动重试：
 * 自动重试会在用户不知情的情况下占用前台服务和存储，反而更吓人。
 */
@Composable
private fun InterruptedBanner(
    modifier: Modifier = Modifier,
    info: InterruptedBatch,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "上次有 ${info.count} 本没导完",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (info.titles.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = info.titles.joinToString("、") +
                        if (info.count > info.titles.size) " 等" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRetry) { Text("重试") }
                TextButton(onClick = onDismiss) { Text("跳过") }
            }
        }
    }
}

/** 等用户选排序规则的待导入项（选完文件、还没开始导） */
private sealed interface PendingImport {
    data class Images(val uris: List<Uri>) : PendingImport
    data class Folder(val uri: Uri) : PendingImport
}

/**
 * 导入前的排序选择。
 *
 * 默认值取「上次用的规则」，用户改一次只影响这一次；要改长期默认请去菜单里的
 * 「导入排序设置」——分开是刻意的，免得一次例外把习惯改掉。
 */
@Composable
private fun ImportSortDialog(
    isFolder: Boolean,
    initialMode: PageSortMode,
    initialAsc: Boolean,
    onConfirm: (PageSortMode, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf(initialMode) }
    var asc by remember { mutableStateOf(initialAsc) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isFolder) "文件夹排序" else "图片排序") },
        text = {
            Column {
                Text(
                    text = "按什么顺序排页。之后也能在书的菜单里改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                ImportSortPrefs.choices.forEach { choice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { mode = choice }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = mode == choice, onClick = { mode = choice })
                        Column(modifier = Modifier.padding(start = 2.dp)) {
                            Text(choice.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                choice.hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = asc, onCheckedChange = { asc = it })
                    Text(
                        text = if (asc) "升序" else "降序",
                        modifier = Modifier.padding(start = 10.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(mode, asc) }) { Text("开始导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 导入默认排序设置：文件夹和图片分开配，互不影响。
 */
@Composable
private fun ImportSortSettingsDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入排序设置") },
        text = {
            Column {
                Text(
                    text = "下次导入时默认用这套规则，导入前还能临时改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                ImportSortSection(
                    label = "文件夹",
                    initialMode = ImportSortPrefs.folderMode(context),
                    initialAsc = ImportSortPrefs.folderAsc(context),
                    onChange = { mode, asc -> ImportSortPrefs.saveFolder(context, mode, asc) }
                )
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                ImportSortSection(
                    label = "图片",
                    initialMode = ImportSortPrefs.imageMode(context),
                    initialAsc = ImportSortPrefs.imageAsc(context),
                    onChange = { mode, asc -> ImportSortPrefs.saveImage(context, mode, asc) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

@Composable
private fun ImportSortSection(
    label: String,
    initialMode: PageSortMode,
    initialAsc: Boolean,
    onChange: (PageSortMode, Boolean) -> Unit
) {
    var mode by remember { mutableStateOf(initialMode) }
    var asc by remember { mutableStateOf(initialAsc) }

    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ImportSortPrefs.choices.forEach { choice ->
                FilterChip(
                    selected = mode == choice,
                    onClick = {
                        mode = choice
                        onChange(choice, asc)
                    },
                    label = { Text(choice.label) }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (asc) "升序" else "降序",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(end = 8.dp)
            )
            Switch(
                checked = asc,
                onCheckedChange = {
                    asc = it
                    onChange(mode, it)
                }
            )
        }
    }
}