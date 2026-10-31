package com.mangashelf.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mangashelf.reader.data.CoverPrefs
import com.mangashelf.reader.data.CoverQuality
import com.mangashelf.reader.data.EyeCarePrefs
import com.mangashelf.reader.data.EyeLevel
import com.mangashelf.reader.data.EyePolicy
import com.mangashelf.reader.data.EyeScope
import com.mangashelf.reader.data.ImportSortPrefs
import com.mangashelf.reader.data.PageSortMode
import com.mangashelf.reader.data.SearchHistory
import com.mangashelf.reader.ui.theme.ThemeMode

/**
 * 统一设置页（v1.5.8）。
 *
 * 之前这些开关分散在书架顶栏的 10 个文字按钮里，屏幕一窄就挤成「…」，
 * 用户根本看不到护眼、封面质量这些入口。这里按「外观 / 阅读 / 导入 / 搜索 / 数据 / 关于」
 * 分组收拢，每行右侧直接显示当前值。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onBack: () -> Unit,
    onBackupNow: () -> Unit,
    onRestoreLocal: () -> Unit,
    onExportZip: () -> Unit,
    onImportZip: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val context = LocalContext.current

    // v1.5.8：设置项一律 runCatching 兜底。SharedPreferences 本身几乎不抛，
    // 但只要有人手动改过 prefs 或跨版本降级安装，类型对不上就是一次冷启动崩溃，
    // 一个设置页不值得把整个 App 带走。
    var layout by remember {
        mutableStateOf(runCatching { ShelfLayoutPrefs.load(context) }.getOrDefault(ShelfLayout.GRID))
    }
    var coverQuality by remember {
        mutableStateOf(runCatching { CoverPrefs.load(context) }.getOrDefault(CoverQuality.THUMB))
    }
    var sensitivity by remember {
        mutableStateOf(
            runCatching { PagePrefs.sensitivity(context) }.getOrDefault(PageSensitivity.MEDIUM)
        )
    }
    var volumeKeys by remember {
        mutableStateOf(runCatching { PagePrefs.volumeKeys(context) }.getOrDefault(true))
    }
    var longPressTurn by remember {
        mutableStateOf(runCatching { PagePrefs.longPressTurn(context) }.getOrDefault(false))
    }
    var folderMode by remember {
        mutableStateOf(
            runCatching { ImportSortPrefs.folderMode(context) }.getOrDefault(PageSortMode.NATURAL)
        )
    }
    var folderAsc by remember {
        mutableStateOf(runCatching { ImportSortPrefs.folderAsc(context) }.getOrDefault(true))
    }
    var imageMode by remember {
        mutableStateOf(
            runCatching { ImportSortPrefs.imageMode(context) }.getOrDefault(PageSortMode.NATURAL)
        )
    }
    var imageAsc by remember {
        mutableStateOf(runCatching { ImportSortPrefs.imageAsc(context) }.getOrDefault(true))
    }
    var eyeHome by remember {
        mutableStateOf(
            runCatching { EyeCarePrefs.load(context, EyeScope.HOME) }.getOrDefault(EyeCarePrefs.State())
        )
    }
    var eyeReader by remember {
        mutableStateOf(
            runCatching { EyeCarePrefs.load(context, EyeScope.READER) }.getOrDefault(EyeCarePrefs.State())
        )
    }
    var searchCount by remember {
        mutableStateOf(runCatching { SearchHistory.load(context).size }.getOrDefault(0))
    }
    var lastBackup by remember { mutableStateOf(runCatching { AutoBackupProxy.lastName(context) }.getOrNull().orEmpty()) }

    var dialog by remember { mutableStateOf(SettingsDialog.NONE) }
    var confirmReset by remember { mutableStateOf(false) }

    val versionName = remember(context) {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("返回", color = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ---------- 外观与显示 ----------
            item { GroupHeader("外观与显示") }
            item {
                SettingsRow("主题", themeMode.label) { dialog = SettingsDialog.THEME }
            }
            item {
                SettingsRow("书架视图", layout.label) { dialog = SettingsDialog.LAYOUT }
            }
            item {
                SettingsRow("封面质量", coverQuality.label) { dialog = SettingsDialog.COVER }
            }

            // ---------- 阅读与手势 ----------
            item { GroupHeader("阅读与手势") }
            item {
                SettingsRow("翻页灵敏度", sensitivity.label) { dialog = SettingsDialog.SENSITIVITY }
            }
            item {
                SwitchRow("音量键翻页", "音量+上一页、音量-下一页", volumeKeys) {
                    volumeKeys = it
                    PagePrefs.setVolumeKeys(context, it)
                }
            }
            item {
                SwitchRow("长按连翻", "按住屏幕边缘连续翻页", longPressTurn) {
                    longPressTurn = it
                    PagePrefs.setLongPressTurn(context, it)
                }
            }
            item {
                SettingsRow("护眼 · 书架", eyeHome.summary()) { dialog = SettingsDialog.EYE_HOME }
            }
            item {
                SettingsRow("护眼 · 阅读器", eyeReader.summary()) { dialog = SettingsDialog.EYE_READER }
            }

            // ---------- 导入 ----------
            item { GroupHeader("导入") }
            item {
                SettingsRow(
                    "文件夹默认排序",
                    folderMode.label + if (folderAsc) " · 正序" else " · 倒序"
                ) { dialog = SettingsDialog.IMPORT_FOLDER }
            }
            item {
                SettingsRow(
                    "图片默认排序",
                    imageMode.label + if (imageAsc) " · 正序" else " · 倒序"
                ) { dialog = SettingsDialog.IMPORT_IMAGE }
            }
            item { SettingsRow("批次队列", "查看正在导入与已完成的批次") { onOpenQueue() } }
            item { SettingsRow("导入历史", "每次导入的页数、失败数与耗时") { onOpenHistory() } }

            // ---------- 搜索 ----------
            item { GroupHeader("搜索") }
            item {
                SettingsRow("搜索历史", if (searchCount == 0) "暂无记录" else "$searchCount 条") {
                    SearchHistory.clear(context)
                    searchCount = 0
                }
            }

            // ---------- 数据与备份 ----------
            item { GroupHeader("数据与备份") }
            item {
                SettingsRow("立即备份数据库", if (lastBackup.isEmpty()) "还没有备份" else "最近：$lastBackup") {
                    onBackupNow()
                    lastBackup = runCatching { AutoBackupProxy.lastName(context) }.getOrNull().orEmpty()
                }
            }
            item { SettingsRow("从最近备份恢复", "只恢复数据库，不含图片") { onRestoreLocal() } }
            item { SettingsRow("导出完整备份", "数据库 + 全部图片，zip 存到所选目录") { onExportZip() } }
            item { SettingsRow("从完整备份恢复", "选择之前导出的 zip") { onImportZip() } }

            // ---------- 关于 ----------
            item { GroupHeader("关于") }
            item { SettingsRow("版本", "v$versionName（versionCode 19）") {} }
            item { SettingsRow("数据库", "Room v8 · 无云端同步 · 不联网") {} }
            item {
                SettingsRow("恢复默认设置", "主题、视图、护眼、手势、排序全部复位") {
                    confirmReset = true
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // ---------------- 各类选择对话框 ----------------
    when (dialog) {
        SettingsDialog.THEME -> ChoiceDialog(
            title = "主题",
            options = ThemeMode.entries.map { it to it.label },
            current = themeMode
        ) {
            onThemeChange(it)
            dialog = SettingsDialog.NONE
        }

        SettingsDialog.LAYOUT -> ChoiceDialog(
            title = "书架视图",
            options = ShelfLayout.entries.map { it to it.label },
            current = layout
        ) {
            layout = it
            ShelfLayoutPrefs.save(context, it)
            dialog = SettingsDialog.NONE
        }

        SettingsDialog.COVER -> ChoiceDialog(
            title = "封面质量",
            options = CoverQuality.entries.map { it to it.label },
            current = coverQuality
        ) {
            coverQuality = it
            CoverPrefs.save(context, it)
            dialog = SettingsDialog.NONE
        }

        SettingsDialog.SENSITIVITY -> ChoiceDialog(
            title = "翻页灵敏度",
            options = PageSensitivity.entries.map { it to it.label },
            current = sensitivity
        ) {
            sensitivity = it
            PagePrefs.setSensitivity(context, it)
            dialog = SettingsDialog.NONE
        }

        SettingsDialog.IMPORT_FOLDER -> SortDialog(
            title = "文件夹默认排序",
            mode = folderMode,
            asc = folderAsc
        ) { m, a ->
            folderMode = m
            folderAsc = a
            ImportSortPrefs.saveFolder(context, m, a)
            dialog = SettingsDialog.NONE
        }

        SettingsDialog.IMPORT_IMAGE -> SortDialog(
            title = "图片默认排序",
            mode = imageMode,
            asc = imageAsc
        ) { m, a ->
            imageMode = m
            imageAsc = a
            ImportSortPrefs.saveImage(context, m, a)
            dialog = SettingsDialog.NONE
        }

        SettingsDialog.EYE_HOME -> EyeDialog(
            title = "护眼 · 书架",
            state = eyeHome
        ) {
            eyeHome = it
            EyeCarePrefs.save(context, EyeScope.HOME, it)
            dialog = SettingsDialog.NONE
        }

        SettingsDialog.EYE_READER -> EyeDialog(
            title = "护眼 · 阅读器",
            state = eyeReader
        ) {
            eyeReader = it
            EyeCarePrefs.save(context, EyeScope.READER, it)
            dialog = SettingsDialog.NONE
        }

        SettingsDialog.NONE -> Unit
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("恢复默认设置") },
            text = { Text("主题、书架视图、封面质量、手势、导入排序、护眼、搜索历史都会复位。图书与阅读进度不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onThemeChange(ThemeMode.SYSTEM)
                    ShelfLayoutPrefs.save(context, ShelfLayout.GRID)
                    CoverPrefs.save(context, CoverQuality.THUMB)
                    PagePrefs.setSensitivity(context, PageSensitivity.MEDIUM)
                    PagePrefs.setVolumeKeys(context, true)
                    PagePrefs.setLongPressTurn(context, false)
                    ImportSortPrefs.saveFolder(context, PageSortMode.NATURAL, true)
                    ImportSortPrefs.saveImage(context, PageSortMode.NATURAL, true)
                    EyeCarePrefs.resetAll(context)
                    SearchHistory.clear(context)
                    layout = ShelfLayout.GRID
                    coverQuality = CoverQuality.THUMB
                    sensitivity = PageSensitivity.MEDIUM
                    volumeKeys = true
                    longPressTurn = false
                    folderMode = PageSortMode.NATURAL
                    folderAsc = true
                    imageMode = PageSortMode.NATURAL
                    imageAsc = true
                    eyeHome = EyeCarePrefs.load(context, EyeScope.HOME)
                    eyeReader = EyeCarePrefs.load(context, EyeScope.READER)
                    searchCount = 0
                    dialog = SettingsDialog.NONE
                }) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("取消") }
            }
        )
    }
}

/** 设置页内部用到的对话框枚举 */
private enum class SettingsDialog {
    NONE, THEME, LAYOUT, COVER, SENSITIVITY, IMPORT_FOLDER, IMPORT_IMAGE, EYE_HOME, EYE_READER
}

/** 因为 AutoBackup 的 list() 在 data 包，这里包一层只是为了「最近一次备份」的展示。 */
private object AutoBackupProxy {
    fun lastName(context: android.content.Context): String =
        com.mangashelf.reader.data.AutoBackup.list(context).firstOrNull()?.name.orEmpty()
}

@Composable
private fun GroupHeader(title: String) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp)
        )
    }
}

@Composable
private fun SettingsRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (value.isNotEmpty()) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    current: T,
    onPick: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onPick(current) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(value) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = value == current, onClick = { onPick(value) })
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(current) }) { Text("关闭") }
        }
    )
}

@Composable
private fun SortDialog(
    title: String,
    mode: PageSortMode,
    asc: Boolean,
    onApply: (PageSortMode, Boolean) -> Unit,
) {
    var m by remember { mutableStateOf(mode) }
    var a by remember { mutableStateOf(asc) }
    AlertDialog(
        onDismissRequest = { onApply(mode, asc) },
        title = { Text(title) },
        text = {
            Column {
                PageSortMode.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { m = option }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = m == option, onClick = { m = option })
                        Text(option.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { a = true }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = a, onClick = { a = true })
                    Text("正序（小在前）", style = MaterialTheme.typography.bodyLarge)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { a = false }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = !a, onClick = { a = false })
                    Text("倒序（大在前）", style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(m, a) }) { Text("应用") }
        },
        dismissButton = {
            TextButton(onClick = { onApply(mode, asc) }) { Text("取消") }
        }
    )
}

@Composable
private fun EyeDialog(
    title: String,
    state: EyeCarePrefs.State,
    onApply: (EyeCarePrefs.State) -> Unit,
) {
    var enabled by remember { mutableStateOf(state.enabled) }
    var level by remember { mutableStateOf(state.level) }
    var policy by remember { mutableStateOf(state.policy) }
    AlertDialog(
        onDismissRequest = { onApply(state) },
        title = { Text(title) },
        text = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { enabled = !enabled }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("开启护眼", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("色温", style = MaterialTheme.typography.labelMedium)
                EyeLevel.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { level = option }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = level == option, onClick = { level = option })
                        Text(option.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("生效方式", style = MaterialTheme.typography.labelMedium)
                EyePolicy.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { policy = option }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = policy == option, onClick = { policy = option })
                        Text(
                            text = if (option == EyePolicy.TIMED) {
                                "${option.label}（${state.fromHour}:00 – ${state.toHour}:00）"
                            } else {
                                option.label
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply(state.copy(enabled = enabled, level = level, policy = policy))
            }) { Text("应用") }
        },
        dismissButton = {
            TextButton(onClick = { onApply(state) }) { Text("取消") }
        }
    )
}
