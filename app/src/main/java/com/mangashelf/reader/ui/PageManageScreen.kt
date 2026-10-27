package com.mangashelf.reader.ui

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.mangashelf.reader.data.PageEntry
import com.mangashelf.reader.data.PageSortMode
import java.io.File

/**
 * 页面管理（v1.5.3）：
 * 缩略图网格里多选 → 上移/下移、设封面、移入回收站、恢复；顶部可换排序规则。
 *
 * 排序只改数据库里的顺序号，不动文件；移入回收站是软删除，页还在磁盘上，
 * 只有「清空回收站」才真正删文件。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PageManageScreen(
    bookId: Long,
    onBack: () -> Unit
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: PageManageViewModel = viewModel(
        key = "pages-$bookId",
        factory = PageManageViewModel.factory(app, bookId)
    )
    val book by vm.book.collectAsStateWithLifecycle()
    val pages by vm.pages.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var sortMenu by remember { mutableStateOf(false) }
    var confirmPurge by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    BackHandler(enabled = selected.isNotEmpty()) { vm.clearSelection() }
    BackHandler(enabled = selected.isEmpty()) { onBack() }

    val visible = remember(pages) { pages.filter { it.deleted == 0 } }
    val deleted = remember(pages) { pages.filter { it.deleted != 0 } }
    val shown = if (deleted.isEmpty() || selected.any { id -> deleted.any { it.id == id } }) {
        // 有已删页被选中时，网格里也要能看到它
        pages
    } else {
        visible
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = book?.title ?: "页面管理",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = buildString {
                                append("${visible.size} 页")
                                if (deleted.isNotEmpty()) append(" · 回收站 ${deleted.size}")
                                stats?.let { append(" · ${formatBytes(it.dirBytes + it.sourceBytes)}") }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (selected.isEmpty()) onBack() else vm.clearSelection() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (selected.isEmpty()) {
                        IconButton(onClick = { vm.selectAll() }) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = "全选")
                        }
                        Box {
                            IconButton(onClick = { sortMenu = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "更多")
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                listOf(
                                    PageSortMode.NATURAL,
                                    PageSortMode.NAME,
                                    PageSortMode.MTIME,
                                    PageSortMode.SIZE
                                ).forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text("按${mode.label}排序") },
                                        onClick = {
                                            sortMenu = false
                                            vm.applySort(mode)
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("恢复导入时的顺序") },
                                    onClick = {
                                        sortMenu = false
                                        vm.resetOrder()
                                    }
                                )
                                if (deleted.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("恢复回收站的 ${deleted.size} 页") },
                                        onClick = {
                                            sortMenu = false
                                            vm.restoreAllDeleted()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("清空回收站（删文件）") },
                                        onClick = {
                                            sortMenu = false
                                            confirmPurge = true
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        TextButton(onClick = { vm.clearSelection() }) { Text("取消") }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (selected.isNotEmpty()) {
                SelectionBar(
                    selectedCount = selected.size,
                    anyDeleted = selected.any { id -> deleted.any { it.id == id } },
                    busy = busy,
                    onMoveUp = { vm.moveSelected(-1) },
                    onMoveDown = { vm.moveSelected(1) },
                    onCover = {
                        val position = visible.indexOfFirst { it.id == selected.first() }
                        if (position >= 0) vm.setCover(position)
                    },
                    onDelete = { vm.deleteSelected() },
                    onRestore = { vm.restoreSelected() }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(shown, key = { it.id }) { page ->
                    PageCell(
                        app = app,
                        folder = book?.folder.orEmpty(),
                        page = page,
                        index = visible.indexOfFirst { it.id == page.id },
                        checked = page.id in selected,
                        onClick = { vm.toggle(page.id) }
                    )
                }
            }

            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }

            if (shown.isEmpty()) {
                Text(
                    text = "这本书还没有页面",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (confirmPurge) {
        AlertDialog(
            onDismissRequest = { confirmPurge = false },
            title = { Text("清空回收站？") },
            text = {
                Text("将彻底删除 ${deleted.size} 页的图片文件，无法恢复。建议先确认这些页确实不要了。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmPurge = false
                    vm.purgeDeleted()
                }) { Text("彻底删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmPurge = false }) { Text("取消") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageCell(
    app: Application,
    folder: String,
    page: PageEntry,
    index: Int,
    checked: Boolean,
    onClick: () -> Unit
) {
    val path = remember(page.relPath, folder) {
        val storage = (app as com.mangashelf.reader.MangaShelfApp).repository.storage
        File(storage.dirOf(folder), page.relPath).absolutePath
    }
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .aspectRatio(0.72f)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .border(
                width = if (checked) 3.dp else 1.dp,
                color = if (checked) accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                shape = RoundedCornerShape(10.dp)
            )
            .combinedClickable(onClick = onClick, onLongClick = onClick)
            .alpha(if (page.deleted != 0) 0.45f else 1f)
            .padding(3.dp)
    ) {
        AsyncImage(
            model = path,
            contentDescription = "第 ${index + 1} 页",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black, RoundedCornerShape(8.dp))
        )
        Text(
            text = if (page.deleted != 0) "已删" else "${index + 1}",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .background(Color(0xAA000000), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp)
        )
        if (page.deleted != 0) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = "已删除",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            )
        }
    }
}

@Composable
private fun SelectionBar(
    selectedCount: Int,
    anyDeleted: Boolean,
    busy: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onCover: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = "已选 $selectedCount 页",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (anyDeleted) {
                    TextButton(onClick = onRestore, enabled = !busy) { Text("恢复") }
                } else {
                    TextButton(onClick = onMoveUp, enabled = !busy) { Text("上移") }
                    TextButton(onClick = onMoveDown, enabled = !busy) { Text("下移") }
                    if (selectedCount == 1) {
                        TextButton(onClick = onCover, enabled = !busy) { Text("设为封面") }
                    }
                    TextButton(onClick = onDelete, enabled = !busy) { Text("移入回收站") }
                }
                Spacer(Modifier.width(4.dp))
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(18.dp).height(18.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}
