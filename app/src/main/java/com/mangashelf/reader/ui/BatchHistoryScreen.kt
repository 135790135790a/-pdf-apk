package com.mangashelf.reader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mangashelf.reader.data.ImportBatch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.5.6：导入历史页。
 *
 * 为什么单独做一页而不是继续用对话框：批次可能攒到几百条，对话框里既不能筛选也不能翻页，
 * 饼图在窄对话框里也画不开。
 *
 * @param batches 全量批次（ViewModel 里已经按 createdAt 倒序）
 */
@Composable
fun BatchHistoryScreen(
    batches: List<ImportBatch>,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit
) {
    var dimension by rememberSaveable { mutableStateOf(0) }        // 0 来源 1 格式 2 结果
    var statusFilter by rememberSaveable { mutableStateOf("") }    // "" 全部
    var visibleCount by rememberSaveable { mutableIntStateOf(20) }
    var confirmClear by remember { mutableStateOf(false) }

    val filtered = remember(batches, statusFilter) {
        if (statusFilter.isEmpty()) batches else batches.filter { it.status == statusFilter }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            // ---- 顶栏 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回"
                    )
                }
                Text(
                    text = "导入历史",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                if (filtered.isNotEmpty()) {
                    TextButton(onClick = onExport) { Text("导出") }
                }
                if (batches.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }) {
                        Text("清空", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            HorizontalDivider()

            if (batches.isEmpty()) {
                EmptyHistory()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        PieSection(
                            batches = filtered,
                            dimension = dimension,
                            onDimensionChange = { dimension = it }
                        )
                    }
                    item {
                        StatusFilterRow(
                            current = statusFilter,
                            onSelect = { statusFilter = it }
                        )
                    }
                    items(filtered.take(visibleCount), key = { it.id }) { batch ->
                        BatchRow(batch)
                    }
                    if (filtered.size > visibleCount) {
                        item {
                            TextButton(
                                onClick = { visibleCount += 20 },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("加载更多（还有 ${filtered.size - visibleCount} 条）")
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空导入记录？") },
            text = { Text("只删记录，已经导进来的书不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun EmptyHistory() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "还没有导入记录",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "导入一本书或一个文件夹后，这里会记下每次导入的结果。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------- 饼图 ----------------

private data class Slice(val label: String, val value: Int, val color: Color)

@Composable
private fun PieSection(
    batches: List<ImportBatch>,
    dimension: Int,
    onDimensionChange: (Int) -> Unit
) {
    val slices = remember(batches, dimension) { buildSlices(batches, dimension) }
    val total = slices.sumOf { it.value }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("来源", "格式", "结果").forEachIndexed { index, label ->
                    FilterChip(
                        selected = dimension == index,
                        onClick = { onDimensionChange(index) },
                        label = { Text(label) }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            if (total == 0) {
                Text(
                    text = "没有数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                DonutChart(
                    slices = slices,
                    modifier = Modifier.size(120.dp)
                )
                Spacer(Modifier.width(20.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    slices.forEach { slice ->
                        LegendRow(slice = slice, total = total)
                    }
                }
            }
        }
    }
}

@Composable
private fun DonutChart(slices: List<Slice>, modifier: Modifier = Modifier) {
    val total = slices.sumOf { it.value }.toFloat()
    val ringWidth = 26f
    Canvas(modifier = modifier) {
        if (total <= 0f) return@Canvas
        val diameter = minOf(size.width, size.height) - ringWidth
        val radius = diameter / 2f
        val topLeft = Offset(
            (size.width - diameter) / 2f,
            (size.height - diameter) / 2f
        )
        var startAngle = -90f
        slices.forEach { slice ->
            val sweep = slice.value / total * 360f
            drawArc(
                color = slice.color,
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = Size(diameter, diameter),
                style = Stroke(width = ringWidth)
            )
            startAngle += sweep
        }
    }
}

@Composable
private fun LegendRow(slice: Slice, total: Int) {
    val percent = if (total > 0) slice.value * 100f / total else 0f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = slice.color,
            modifier = Modifier.size(10.dp)
        ) {}
        Spacer(Modifier.width(8.dp))
        Text(
            text = slice.label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${slice.value} · ${"%.0f".format(percent)}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 三个维度的分组规则。颜色只取主题里的几个语义色，保证夜晚模式也看得清。 */
private fun buildSlices(batches: List<ImportBatch>, dimension: Int): List<Slice> {
    val primary = Color(0xFF4C8DF6)
    val green = Color(0xFF3DBE7A)
    val orange = Color(0xFFF0A030)
    val red = Color(0xFFE05B5B)
    val gray = Color(0xFF8A8A99)

    return when (dimension) {
        0 -> listOf(
            Slice("批量导入", batches.count { it.totalBooks > 1 }, primary),
            Slice("单本导入", batches.count { it.totalBooks <= 1 }, gray)
        )
        1 -> listOf(
            Slice("文件夹", batches.count { it.sourceType == "FOLDER" }, primary),
            Slice("PDF", batches.count { it.sourceType == "PDF" }, orange),
            Slice("图片", batches.count { it.sourceType == "IMAGE" }, green),
            Slice("CBZ", batches.count { it.sourceType == "CBZ" }, Color(0xFF9B6BE0))
        )
        else -> listOf(
            Slice("成功", batches.count { it.status == ImportBatch.DONE }, green),
            Slice("部分失败", batches.count { it.status == ImportBatch.PARTIAL }, orange),
            Slice("失败", batches.count { it.status == ImportBatch.FAILED }, red),
            Slice("进行中", batches.count { it.status in ImportBatch.UNFINISHED }, primary),
            Slice("已取消", batches.count { it.status == ImportBatch.CANCELLED }, gray)
        )
    }.filter { it.value > 0 }
}

@Composable
private fun StatusFilterRow(current: String, onSelect: (String) -> Unit) {
    val options = listOf(
        "" to "全部",
        ImportBatch.DONE to "成功",
        ImportBatch.PARTIAL to "部分失败",
        ImportBatch.FAILED to "失败",
        ImportBatch.PAUSED to "已暂停"
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = current == value,
                onClick = { onSelect(value) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun BatchRow(batch: ImportBatch) {
    val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = fmt.format(Date(batch.createdAt)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusText(batch.status),
                    style = MaterialTheme.typography.labelMedium,
                    color = when (batch.status) {
                        ImportBatch.DONE -> MaterialTheme.colorScheme.primary
                        ImportBatch.FAILED -> MaterialTheme.colorScheme.error
                        ImportBatch.PARTIAL -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = sourceText(batch.sourceType),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = buildString {
                    append("${batch.doneBooks}/${batch.totalBooks} 本")
                    if (batch.totalPages > 0) append(" · ${batch.donePages}/${batch.totalPages} 页")
                    if (batch.failedBooks > 0) append(" · 失败 ${batch.failedBooks}")
                    if (batch.skippedBooks > 0) append(" · 跳过 ${batch.skippedBooks}")
                    if (batch.elapsedMs > 0) {
                        append(" · 用时 ${formatDuration(batch.elapsedMs)}")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (batch.failReason != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = batch.failReason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun statusText(status: String): String = when (status) {
    ImportBatch.QUEUED -> "排队中"
    ImportBatch.RUNNING -> "导入中"
    ImportBatch.PAUSED -> "已暂停"
    ImportBatch.DONE -> "已完成"
    ImportBatch.PARTIAL -> "部分失败"
    ImportBatch.FAILED -> "失败"
    ImportBatch.CANCELLED -> "已取消"
    else -> status
}

private fun sourceText(source: String): String = when (source) {
    "FOLDER" -> "文件夹"
    "PDF" -> "PDF"
    "IMAGE" -> "图片"
    "CBZ" -> "CBZ"
    else -> source
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return when {
        seconds < 60 -> "${seconds}秒"
        seconds < 3600 -> "${seconds / 60}分${seconds % 60}秒"
        else -> "${seconds / 3600}时${seconds % 3600 / 60}分"
    }
}
