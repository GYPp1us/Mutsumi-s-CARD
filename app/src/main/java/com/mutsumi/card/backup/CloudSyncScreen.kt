package com.mutsumi.card.backup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mutsumi.card.draw.SlideToComplete
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SafeCloudConnectionSummary(state: BackupUiState, viewModel: BackupViewModel) {
    val index = state.libraryIndex
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("云端资料库", style = MaterialTheme.typography.titleMedium)
                Text(if (index == null) "尚未建立 · 首次推送也会先预览" else
                    "${index.document.snapshot.decks.size} 个卡组 · ${index.document.snapshot.cards.size} 张卡片 · ${syncTime(index.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall)
            }
            TextButton(viewModel::refreshCloud, enabled = !state.isBusy) { Text("刷新") }
        }
        Text("增量操作仅新增和更新。同一张卡片在两端内容不同时，按来源更新，预览会展示变更。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(CloudSyncMode.Pull, CloudSyncMode.Push).forEach { mode ->
                Button({ viewModel.previewSync(mode) }, Modifier.weight(1f), enabled = !state.isBusy,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)) { Text(mode.label) }
            }
        }
        HorizontalDivider()
        Text("删除清理", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
        Text("只删除目标端独有的项目。先核对清单，再滑动确认。", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(CloudSyncMode.PullDelete, CloudSyncMode.PushDelete).forEach { mode ->
                OutlinedButton({ viewModel.previewSync(mode) }, Modifier.weight(1f), enabled = !state.isBusy,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(mode.label) }
            }
        }
        state.syncProgress?.let { SyncProgress(it) }
        if (index?.revision?.startsWith("legacy-") == true) {
            Text("正在读取旧版备份。首次推送会迁移卡面到独立资料库，请先升级其他设备，再继续在设备间备份。旧版原件保留。",
                style = MaterialTheme.typography.bodySmall)
        }
        state.latestCloudEvent?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            color = if (state.cloudEventSucceeded == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
    }
}

@Composable
internal fun SafeCloudVersions(state: BackupUiState, viewModel: BackupViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("历史版本", style = MaterialTheme.typography.titleSmall)
        Text("历史拉取仍只新增和更新；本地独有内容保留。", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.cloudSnapshots.forEach { summary ->
                OutlinedCard(Modifier.width(220.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(syncTime(summary.createdAt), fontWeight = FontWeight.SemiBold)
                        Text("${summary.deckCount} 个卡组 · ${summary.cardCount} 张卡片", style = MaterialTheme.typography.bodySmall)
                        Text("新增/更新 ${summary.addedOrChangedCount} · 删除 ${summary.deletedCount}", style = MaterialTheme.typography.labelSmall)
                        TextButton({ viewModel.previewSync(CloudSyncMode.Pull, summary.id) }, enabled = !state.isBusy) { Text("预览增量拉取") }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun CloudSyncPreviewDialog(preview: CloudSyncPreview, busy: Boolean, progress: CloudSyncProgress?, onDismiss: () -> Unit, onExecute: (Boolean) -> Unit) {
    var query by rememberSaveable(preview.token) { mutableStateOf("") }
    var filter by rememberSaveable(preview.token) { mutableStateOf<String?>(null) }
    var limit by rememberSaveable(preview.token, query, filter) { mutableIntStateOf(40) }
    val entries = remember(preview.token, query, filter) {
        preview.plan.entries.filter { (filter == null || it.change.name == filter) &&
            (query.isBlank() || it.title.contains(query, ignoreCase = true) || it.location.contains(query, ignoreCase = true)) }
    }
    val listState = rememberLazyGridState()
    LaunchedEffect(query, filter) { listState.scrollToItem(0) }
    val last by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
    LaunchedEffect(last, limit, entries.size) {
        if (last >= limit - 5 && limit < entries.size) limit = (limit + 40).coerceAtMost(entries.size)
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = !busy, dismissOnClickOutside = false)) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        val imeVisible = WindowInsets.isImeVisible
        Surface(Modifier.fillMaxSize().safeDrawingPadding().testTag("cloud-sync-preview"), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints {
                val short = maxHeight < 420.dp
                Column(Modifier.fillMaxSize().padding(if (short) 10.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!imeVisible) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (short) {
                            Text("${preview.mode.label}预览", style = MaterialTheme.typography.titleMedium)
                            Text("${if (preview.mode.isPull) "云端 → 本地" else "本地 → 云端"} · ${preview.plan.entries.size} 项 · ${syncBytes(preview.transferBytes)}",
                                Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else {
                        Column(Modifier.weight(1f)) {
                            Text("${preview.mode.label}预览", style = MaterialTheme.typography.titleLarge)
                            Text("${if (preview.mode.isPull) "云端 → 本地" else "本地 → 云端"} · ${preview.plan.entries.size} 项变更 · 传输 ${syncBytes(preview.transferBytes)}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        }
                        TextButton(onDismiss, enabled = !busy) { Text("取消") }
                    }
                    if (!short && !imeVisible) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CloudSyncChange.entries.forEach { change ->
                                Surface(Modifier.weight(1f), color = if (change == CloudSyncChange.Delete) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
                                    shape = MaterialTheme.shapes.small) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text(change.label, style = MaterialTheme.typography.labelMedium)
                                        Text(preview.plan.entries.count { it.change == change }.toString(), style = MaterialTheme.typography.titleLarge)
                                    }
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(query, { query = it }, Modifier.weight(1f), placeholder = { Text("搜索卡片或卡组") }, singleLine = true,
                            enabled = !busy, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }))
                        Text("${minOf(limit, entries.size)}/${entries.size}", style = MaterialTheme.typography.labelSmall)
                    }
                    if (!short && !imeVisible) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(filter == null, { filter = null }, { Text("全部") })
                        CloudSyncChange.entries.forEach { change -> FilterChip(filter == change.name, { filter = change.name }, { Text(change.label) }) }
                    }
                    LazyVerticalGrid(columns = GridCells.Adaptive(420.dp), modifier = Modifier.weight(1f).fillMaxWidth().testTag("cloud-sync-preview-list"), state = listState,
                        verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (entries.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(if (preview.requiresMigration) "记录内容一致。首次推送会为 ${preview.plan.document.snapshot.cards.size} 张卡片建立安全资料库，迁移卡面，保留旧备份。"
                                else if (preview.plan.entries.isEmpty()) "没有需要执行的变更" else "没有匹配项目", Modifier.padding(16.dp))
                        }
                        items(entries.take(limit), key = { "${it.kind}:${it.id}" }) { entry ->
                            SyncEntry(entry, compact = short)
                        }
                        if (limit < entries.size) item(span = { GridItemSpan(maxLineSpan) }) {
                            TextButton({ limit = (limit + 40).coerceAtMost(entries.size) }, Modifier.fillMaxWidth()) { Text("继续加载 40 项") }
                        }
                    }
                    if (busy && progress != null) SyncProgress(progress)
                    else if (!imeVisible && preview.mode.deletes) {
                        Text("将删除${if (preview.mode.isPull) "本地" else "云端"}独有的全部 ${preview.plan.entries.size} 项，含归档内容。筛选不改变执行范围。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        SlideToComplete(!busy && entries.isNotEmpty(), Modifier.widthIn(max = 420.dp).fillMaxWidth().align(Alignment.End),
                            label = "确认${preview.mode.label}（${preview.plan.entries.size} 项）", tag = "confirm-sync-delete", destructive = true) { onExecute(true) }
                    } else if (!imeVisible) Button({ onExecute(false) }, Modifier.widthIn(max = 420.dp).fillMaxWidth().align(Alignment.End),
                        enabled = !busy && (preview.plan.entries.isNotEmpty() || preview.requiresMigration)) {
                            Text(if (preview.requiresMigration) "执行增量推送（首次迁移）" else "执行${preview.mode.label}（全部 ${preview.plan.entries.size} 项）")
                        }
                }
            }
        }
    }
}

@Composable
private fun SyncEntry(entry: CloudSyncEntry, compact: Boolean) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(if (compact) 7.dp else 10.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 3.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.change.label, style = MaterialTheme.typography.labelMedium,
                    color = if (entry.change == CloudSyncChange.Delete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Text(entry.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(entry.kind.label, style = MaterialTheme.typography.labelSmall)
            }
            Text("${entry.location.ifBlank { "全部卡组" }} · ${if (entry.archived) "已归档" else "使用中"}" +
                if (entry.kind == CloudConflictKind.Card) " · ${if (entry.doubleSided) "双面" else "背面"} · 复习 ${entry.seenCount} 次 · ${syncBytes(entry.imageBytes)}" else "",
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall)
            entry.previousTitle?.let { Text("原文字：$it", style = MaterialTheme.typography.bodySmall) }
            if (entry.details.isNotEmpty()) Text(entry.details.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
            if (entry.change == CloudSyncChange.Update && entry.targetUpdatedAt != null) {
                Text("目标更新 ${syncTime(entry.targetUpdatedAt)} → 来源更新 ${syncTime(entry.updatedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.targetUpdatedAt > entry.updatedAt) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("创建 ${syncTime(entry.createdAt)} · 更新 ${syncTime(entry.updatedAt)} · ${entry.id.take(8)}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SyncProgress(progress: CloudSyncProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth().testTag("sync-progress"))
        Text("${progress.stage} · ${progress.completed}/${progress.total}" + if (progress.totalBytes > 0) " · ${syncBytes(progress.bytes)}/${syncBytes(progress.totalBytes)}" else "",
            style = MaterialTheme.typography.labelSmall)
    }
}

private fun syncTime(value: Long) = if (value == 0L) "—" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(value))
private fun syncBytes(value: Long) = when {
    value >= 1024 * 1024 -> "%.1f MiB".format(Locale.CHINA, value / 1048576.0)
    value >= 1024 -> "%.1f KiB".format(Locale.CHINA, value / 1024.0)
    else -> "$value B"
}
