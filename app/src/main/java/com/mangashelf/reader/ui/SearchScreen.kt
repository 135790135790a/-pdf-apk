package com.mangashelf.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mangashelf.reader.data.Book
import java.io.File

/**
 * 独立搜索页（v1.5.8）。
 *
 * 之前搜索是顶栏里原地展开的输入框：没有历史区、没有热门、结果直接替换书架列表，
 * 退出后关键词和滚动位置都丢。这里按参考图的布局改成全屏页，
 * 空查询时给历史和热门，有查询时只显示结果，返回时书架保持原状。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<Book>,
    covers: Map<Long, String?>,
    history: List<String>,
    hotTags: List<String>,
    onDeleteHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onOpenBook: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        // 进页面就聚焦并弹键盘，省掉用户再点一下输入框
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ---------- 顶部：返回 + 输入框 + 搜索 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    placeholder = { Text("搜索书名 / 作者 / 标签") },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = "清除")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() })
                )
                TextButton(onClick = { keyboard?.hide() }) {
                    Text("搜索", fontWeight = FontWeight.Medium)
                }
            }

            if (query.isBlank()) {
                HomeSection(
                    history = history,
                    hotTags = hotTags,
                    onPick = onQueryChange,
                    onDeleteHistory = onDeleteHistory,
                    onClearHistory = onClearHistory
                )
            } else {
                if (results.isEmpty()) {
                    EmptyResult(query = query, onClear = { onQueryChange("") })
                } else {
                    Text(
                        text = "找到 ${results.size} 本",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 6.dp)
                    )
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(results, key = { it.id }) { book ->
                            ResultRow(
                                book = book,
                                coverPath = covers[book.id],
                                onClick = { onOpenBook(book.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeSection(
    history: List<String>,
    hotTags: List<String>,
    onPick: (String) -> Unit,
    onDeleteHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "搜索历史",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (history.isNotEmpty()) {
                TextButton(onClick = onClearHistory) {
                    Text("清空", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (history.isEmpty()) {
            Text(
                "还没有搜索记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                history.forEach { term ->
                    Chip(text = term, onClick = { onPick(term) }, onLongClick = { onDeleteHistory(term) })
                }
            }
            Text(
                "长按标签可删除单条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            "热门标签",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        if (hotTags.isEmpty()) {
            Text(
                "书架里的作者和标签会出现在这里",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                hotTags.forEach { tag ->
                    Chip(text = tag, onClick = { onPick(tag) }, onLongClick = null)
                }
            }
        }
    }
}

/** 长按可选的点击修饰符：没有长按回调时退化成普通点击，避免多注册一个手势识别器。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
): Modifier =
    if (onLongClick == null) {
        this.clickable { onClick() }
    } else {
        this.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    }

@Composable
private fun Chip(text: String, onClick: () -> Unit, onLongClick: (() -> Unit)?) {    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.combinedClickableCompat(onClick = onClick, onLongClick = onLongClick)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun EmptyResult(query: String, onClear: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "没有匹配的书记",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "「$query」没有找到结果，换个书名或作者试试",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onClear) { Text("清除搜索") }
    }
}

@Composable
private fun ResultRow(book: Book, coverPath: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp, 72.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
        ) {
            if (!coverPath.isNullOrBlank()) {
                AsyncImage(
                    model = File(coverPath),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                )
            } else {
                Text(
                    text = book.title.take(1),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                book.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            val meta = buildString {
                append("${book.pageCount} 页")
                if (book.author.isNotBlank()) append(" · ${book.author}")
                if (book.tags.isNotBlank()) append(" · ${book.tags}")
            }
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (book.lastPage > 0) {
                Text(
                    "读到第 ${book.lastPage + 1} 页",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
