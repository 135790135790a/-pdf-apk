package com.mangashelf.reader.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mangashelf.reader.data.DirSort
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件浏览页（v1.5.7 新增）。
 *
 * 存在的原因：系统 SAF 选择器里没法「长按多选几个文件夹再一起导入」，
 * 也不记得上次排的序。这一页就是给这两件事补个界面。
 *
 * 手势约定：
 * - 单击目录 = 进入
 * - 长按 = 进入多选（同时选中这一项）
 * - 多选态下单击 = 勾选/取消
 * - 返回 = 退一层；已经在根目录时退回书架
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FileBrowserScreen(
    onBack: () -> Unit,
    onImport: () -> Unit,
) {
    val vm: FileBrowserViewModel = viewModel()
    val shelfVm: ShelfViewModel = viewModel()

    val rootUri by vm.rootUri.collectAsStateSafe()
    val stack by vm.stack.collectAsStateSafe()
    val items by vm.items.collectAsStateSafe()
    val loading by vm.loading.collectAsStateSafe()
    val error by vm.error.collectAsStateSafe()
    val sortPair by vm.sort.collectAsStateSafe()
    val selectMode by vm.selectMode.collectAsStateSafe()
    val selected by vm.selected.collectAsStateSafe()
    val highlight by vm.highlight.collectAsStateSafe()

    val listState = rememberLazyListState()
    var sortMenuOpen by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<List<Pair<String, String>>?>(null) }

    val pickRoot = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.openRoot(uri)
    }

    LaunchedEffect(Unit) { vm.restore() }

    // 进入某一层（或退回上级）后，滚动到上次的位置
    LaunchedEffect(stack.size) {
        val idx = stack.lastOrNull()?.scrollIndex ?: 0
        if (idx > 0) runCatching { listState.scrollToItem(idx) }
    }

    BackHandler {
        if (selectMode) vm.exitSelectMode()
        else if (!vm.goUp()) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectMode) vm.exitSelectMode()
                        else if (!vm.goUp()) onBack()
                    }) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
                title = {
                    val cur = stack.lastOrNull()
                    Text(
                        text = if (stack.isEmpty()) "文件" else nameOf(cur?.uri),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                actions = {
                    if (rootUri != null) {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "排序")
                        }
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            DirSort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.label) },
                                    leadingIcon = {
                                        if (sortPair.first == s) Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                                        else Spacer(Modifier.size(18.dp))
                                    },
                                    onClick = {
                                        val asc = if (sortPair.first == s) !sortPair.second else true
                                        vm.setSort(s, asc)
                                        sortMenuOpen = false
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(if (sortPair.second) "↑ 升序（点切换）" else "↓ 降序（点切换）") },
                                onClick = { vm.setSort(sortPair.first, !sortPair.second) }
                            )
                        }
                        if (!selectMode) {
                            TextButton(onClick = {
                                val first = items.firstOrNull()
                                if (first != null) vm.enterSelectMode(first.docId)
                            }) { Text("选择") }
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (selectMode) {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        // 目录的 size 是 0，这里的 MB 只是「已知大小之和」，给个数量级参考
                        val pickedSize = vm.selectedSizeBytes()
                        Text(
                            if (pickedSize > 0) {
                                "已选 ${selected.size} 项 · 已知大小约 ${"%.1f".format(pickedSize / 1048576.0)} MB"
                            } else {
                                "已选 ${selected.size} 项"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { vm.selectAll() }) { Text("全选") }
                            TextButton(onClick = { vm.invertSelection() }) { Text("反选") }
                            TextButton(onClick = { vm.exitSelectMode() }) { Text("退出") }
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = {
                                    val docs = vm.selectedDocs()
                                    if (docs.isNotEmpty()) pendingImport = docs
                                },
                                enabled = selected.isNotEmpty()
                            ) { Text("导入（${selected.size}）") }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                rootUri == null -> EmptyRoot(onPick = { pickRoot.launch(null) })
                else -> Column(Modifier.fillMaxSize()) {
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(items, key = { it.docId }) { item ->
                            FileRow(
                                item = item,
                                highlighted = highlight.isNotEmpty() && item.docId == highlight,
                                selectMode = selectMode,
                                checked = selected.contains(item.docId),
                                onClick = {
                                    if (selectMode) {
                                        vm.toggleSelect(item.docId)
                                    } else if (item.isDir) {
                                        vm.rememberScroll(listState.firstVisibleItemIndex)
                                        vm.enter(item, listState.firstVisibleItemIndex)
                                    }
                                },
                                onLongClick = {
                                    vm.rememberScroll(listState.firstVisibleItemIndex)
                                    vm.enterSelectMode(item.docId)
                                }
                            )
                            HorizontalDivider(Modifier.padding(start = 56.dp))
                        }
                        if (items.isEmpty() && !loading) {
                            item {
                                Text(
                                    "这个文件夹是空的",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(40.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 导入前确认：告诉用户会导入几本、走的是什么规则
    pendingImport?.let { docs ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("导入 ${docs.size} 个文件夹") },
            text = {
                Text(
                    "每个文件夹算一本书，里面的图片（含子目录）按相对路径自然顺序排页。\n\n" +
                        "导入会排进批次队列，可在书架顶部查看进度。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    vm.exitSelectMode()
                    rootUri?.let { shelfVm.importDocs(it, docs) }
                    onImport()
                }) { Text("开始导入") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun EmptyRoot(onPick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text("先选一个根目录", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "选完之后可以在这里浏览、排序、长按多选，再批量导入。\n只会读你选的那个目录，不会碰别的地方。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        androidx.compose.material3.Button(onClick = onPick) { Text("选择文件夹") }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    item: FileItem,
    highlighted: Boolean,
    selectMode: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bg = when {
        highlighted -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        checked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        else -> MaterialTheme.colorScheme.surface
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectMode) {
            Checkbox(checked = checked, onCheckedChange = { onClick() })
            Spacer(Modifier.width(4.dp))
        }
        Icon(
            imageVector = if (item.isDir) Icons.Filled.KeyboardArrowRight else Icons.Filled.List,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = if (item.isDir) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal
            )
            val sub = buildString {
                if (item.isDir) append("文件夹") else append(formatSize(item.sizeBytes))
                if (item.modifiedAt > 0) {
                    append(" · ")
                    append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(item.modifiedAt)))
                }
            }
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (highlighted) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "上次打开",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}

/** 从 tree uri 里取目录名，取不到就显示末段 */
private fun nameOf(uri: String?): String {
    if (uri.isNullOrBlank()) return "文件"
    return runCatching {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(uri))
        docId.substringAfterLast('/').substringAfterLast(':').ifBlank { docId }
    }.getOrDefault("文件")
}

/** 少写一堆 `.collectAsState()` 的导入 */
@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateSafe() =
    this.collectAsState()
