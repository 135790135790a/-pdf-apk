package com.mangashelf.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mangashelf.reader.data.ImportBatch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.5.6：批次队列。
 *
 * 多文件夹导入时，每个文件夹是一批，这里是它们的实时状态：
 * 排队中 / 导入中 / 已暂停 / 已成功 / 部分失败 / 失败 / 已取消。
 * 还没开始的批次可以「跳过」；已经失败的可以「只重试这一批」。
 */
@Composable
fun BatchQueueDialog(
    rows: List<BatchRow>,
    onSkip: (String) -> Unit,
    onRetry: (BatchRow) -> Unit,
    onDismiss: () -> Unit
) {
    val finished = rows.count { it.finished }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入队列（${finished}/${rows.size} 批）") },
        text = {
            if (rows.isEmpty()) {
                Text("队列是空的。用「分批导入」一次选多个文件夹，每个文件夹会成为一批。")
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(rows, key = { it.batchId }) { row ->
                        BatchQueueRow(row, onSkip, onRetry)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun BatchQueueRow(
    row: BatchRow,
    onSkip: (String) -> Unit,
    onRetry: (BatchRow) -> Unit
) {
    val done = row.doneBooks + row.failedBooks + row.skippedBooks
    val fraction = if (row.totalBooks > 0) done.toFloat() / row.totalBooks else 0f
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = row.statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (row.status) {
                        ImportBatch.RUNNING -> MaterialTheme.colorScheme.primary
                        ImportBatch.FAILED -> MaterialTheme.colorScheme.error
                        ImportBatch.PARTIAL -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Text(
                text = "$done/${row.totalBooks} 本" +
                    if (row.failedBooks > 0) " · 失败 ${row.failedBooks}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (row.totalBooks > 0) {
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!row.finished && row.status != ImportBatch.RUNNING) {
                    TextButton(onClick = { onSkip(row.batchId) }) { Text("跳过") }
                }
                if (row.status == ImportBatch.FAILED || row.status == ImportBatch.PARTIAL) {
                    TextButton(onClick = { onRetry(row) }) { Text("只重试这一批") }
                }
            }
        }
    }
}

/** v1.5.6：导入历史（import_batches 表里最近的批次） */
@Composable
fun ImportHistoryDialog(
    rows: List<ImportBatch>,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    /** v1.5.6：导出 CSV，null 表示不显示导出按钮 */
    onExport: (() -> Unit)? = null
) {
    val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入历史") },
        text = {
            if (rows.isEmpty()) {
                Text("还没有导入记录。")
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(rows, key = { it.id }) { batch ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = fmt.format(Date(batch.createdAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = statusText(batch.status),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (batch.status) {
                                        ImportBatch.DONE -> MaterialTheme.colorScheme.primary
                                        ImportBatch.FAILED -> MaterialTheme.colorScheme.error
                                        ImportBatch.PARTIAL -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "${batch.doneBooks}/${batch.totalBooks} 本",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            if (batch.failReason != null) {
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
            }
        },
        confirmButton = {
            Row {
                if (rows.isNotEmpty() && onExport != null) {
                    TextButton(onClick = onExport) { Text("导出报告") }
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = if (rows.isNotEmpty()) {
            { TextButton(onClick = onClear) { Text("清空记录") } }
        } else null
    )
}

private fun statusText(status: String): String = when (status) {
    ImportBatch.QUEUED -> "排队中"
    ImportBatch.RUNNING -> "导入中"
    ImportBatch.PAUSED -> "已暂停"
    ImportBatch.DONE -> "成功"
    ImportBatch.PARTIAL -> "部分失败"
    ImportBatch.FAILED -> "失败"
    ImportBatch.CANCELLED -> "已取消"
    else -> status
}
