package com.mutsumi.card.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.IOException
import android.graphics.BitmapFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.mutsumi.card.ui.components.FeedbackController

@Composable
fun BackupScreen(
    viewModel: BackupViewModel,
    feedback: FeedbackController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { feedback.show(it) }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri == null) {
            viewModel.onExportDocumentResult(null)
        } else {
            try {
                val output = context.contentResolver.openOutputStream(uri, "w")
                if (output == null) viewModel.onExportAccessFailure(IOException("无法打开导出目标"))
                else viewModel.onExportDocumentResult(output)
            } catch (error: IOException) {
                viewModel.onExportAccessFailure(error)
            } catch (error: SecurityException) {
                viewModel.onExportAccessFailure(error)
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) {
            viewModel.onImportDocumentResult(null)
        } else {
            try {
                val input = context.contentResolver.openInputStream(uri)
                if (input == null) viewModel.onImportAccessFailure(IOException("无法打开导入来源"))
                else viewModel.onImportDocumentResult(input)
            } catch (error: IOException) {
                viewModel.onImportAccessFailure(error)
            } catch (error: SecurityException) {
                viewModel.onImportAccessFailure(error)
            }
        }
    }

    LaunchedEffect(viewModel) { viewModel.initializeCloud() }

    state.pendingRestorePreview?.let { preview ->
        RestorePreviewDialog(
            preview = preview,
            enabled = !state.isBusy,
            onDismiss = viewModel::dismissRestorePreview,
            onConfirm = viewModel::confirmRestorePreview,
        )
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= 680.dp && maxWidth > maxHeight
        if (wide) {
            Row(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1.2f).fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PageHeading(state)
                    CloudBackupSection(state, viewModel)
                }
                Column(
                    modifier = Modifier.weight(0.8f).fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LocalBackupSection(
                        enabled = !state.isBusy,
                        onExport = { exportLauncher.launch("mutsumi-card-backup.zip") },
                        onImport = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                    )
                    RecentBackupSection(state.latestCloudEvent)
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PageHeading(state)
                CloudBackupSection(state, viewModel)
                LocalBackupSection(
                    enabled = !state.isBusy,
                    onExport = { exportLauncher.launch("mutsumi-card-backup.zip") },
                    onImport = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                )
                RecentBackupSection(state.latestCloudEvent)
            }
        }
    }
}

@Composable
private fun PageHeading(state: BackupUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("备份", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("云端增量备份与本地归档", style = MaterialTheme.typography.bodySmall)
        }
        if (state.isBusy) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }
    Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CloudBackupSection(state: BackupUiState, viewModel: BackupViewModel) {
    BackupSection(
        title = "云端备份",
        subtitle = "仅上传发生变化的卡片和图片",
        trailing = {
            if (state.isCloudConfigured) {
                IconButton(onClick = viewModel::toggleCloudConfig, enabled = !state.isBusy) {
                    Icon(Icons.Outlined.Settings, contentDescription = "连接设置")
                }
            }
        },
    ) {
        if (state.isEditingCloudConfig) {
            CloudConnectionForm(state, viewModel)
        } else {
            CloudConnectionSummary(state, viewModel)
        }
        if (state.isCloudConfigured && state.cloudSnapshots.isNotEmpty()) {
            CloudVersions(state, viewModel)
        }
    }
}

@Composable
private fun CloudConnectionForm(state: BackupUiState, viewModel: BackupViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.cloudServerUrl,
            onValueChange = viewModel::setCloudServerUrl,
            label = { Text("WebDAV 地址") },
            placeholder = { Text("https://example.com/dav") },
            singleLine = true,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = state.cloudUsername,
                onValueChange = viewModel::setCloudUsername,
                label = { Text("用户名") },
                singleLine = true,
                enabled = !state.isBusy,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.cloudPassword,
                onValueChange = viewModel::setCloudPassword,
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                enabled = !state.isBusy,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = state.cloudRemoteDirectory,
            onValueChange = viewModel::setCloudRemoteDirectory,
            label = { Text("远端目录") },
            singleLine = true,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = viewModel::saveCloudConfig,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存并连接") }
    }
}

@Composable
private fun CloudConnectionSummary(state: BackupUiState, viewModel: BackupViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.size(38.dp),
            ) {
                Icon(
                    Icons.Outlined.Cloud,
                    contentDescription = null,
                    modifier = Modifier.padding(9.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(state.cloudUsername, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${state.cloudServerUrl} / ${state.cloudRemoteDirectory}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("本次变化", style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            "卡组 ${state.cloudDeckCount}（+${state.cloudAddedOrChangedDeckCount}/-${state.cloudDeletedDeckCount}）",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "+${state.cloudAddedOrChangedCount}",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "-${state.cloudDeletedCount}",
                            color = Color(0xFFA54838),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                state.cloudSnapshots.firstOrNull()?.let { latest ->
                    Text(formatTime(latest.createdAt), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = viewModel::backupToCloud,
                enabled = !state.isBusy,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.CloudUpload, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("立即增量备份")
            }
            OutlinedButton(
                onClick = { state.cloudSnapshots.firstOrNull()?.let { viewModel.openRestorePreview(it.id) } },
                enabled = !state.isBusy && state.cloudSnapshots.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("从云端恢复")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ToggleDeleteButton("推送删除", state.pushDelete, !state.isBusy, viewModel::setPushDelete, Modifier.weight(1f))
            ToggleDeleteButton("拉取删除", state.pullDelete, !state.isBusy, viewModel::setPullDelete, Modifier.weight(1f))
        }
        state.latestCloudEvent?.let { event ->
            Surface(
                color = if (state.cloudEventSucceeded == false) Color(0xFFFFE4E0) else Color(0xFFE4F4E9),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (state.cloudEventSucceeded == false) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = if (state.cloudEventSucceeded == false) Color(0xFF9B2C20) else Color(0xFF21633A),
                    )
                    Text(
                        event,
                        color = if (state.cloudEventSucceeded == false) Color(0xFF9B2C20) else Color(0xFF21633A),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun CloudVersions(state: BackupUiState, viewModel: BackupViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("云端版本", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            "左右滑动查看，恢复时导入为本地副本",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.cloudSnapshots.forEachIndexed { index, snapshot ->
                Surface(
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(7.dp),
                    modifier = Modifier.widthIn(min = 210.dp, max = 240.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatTime(snapshot.createdAt), fontWeight = FontWeight.Bold)
                            if (index == 0) Text("当前", color = MaterialTheme.colorScheme.primary)
                        }
                        Text(
                            "卡组 ${snapshot.deckCount}（+${snapshot.addedOrChangedDeckCount}/-${snapshot.deletedDeckCount}） · 卡片 ${snapshot.cardCount}（+${snapshot.addedOrChangedCount}/-${snapshot.deletedCount}）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (index != 0) {
                            OutlinedButton(
                                onClick = { viewModel.openRestorePreview(snapshot.id) },
                                enabled = !state.isBusy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("恢复")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleDeleteButton(
    text: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = { onCheckedChange(!checked) },
        enabled = enabled,
        modifier = modifier,
        border = BorderStroke(1.dp, if (checked) Color(0xFFC53B32) else MaterialTheme.colorScheme.outline),
    ) {
        Text(text, color = if (checked) Color(0xFFC53B32) else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun RestorePreviewDialog(
    preview: CloudRestorePreview,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (enabled) onDismiss() },
        title = { Text("恢复预览") },
        text = {
            Column(
            modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("快照时间：${formatTime(preview.createdAt)}", fontWeight = FontWeight.Bold)
                Text("卡组 ${preview.stats.deckCount}，卡片 ${preview.stats.cardCount}")
                Text("卡组变化 +${preview.stats.addedOrChangedDeckCount}/-${preview.stats.deletedDeckCount}，卡片变化 +${preview.stats.addedOrChangedCount}/-${preview.stats.deletedCount}")
                preview.deletionWarning?.let { warning ->
                    Text(warning, color = Color(0xFFC53B32), fontWeight = FontWeight.Bold)
                }
                preview.cards.forEach { card ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${card.deckName} · ${card.keyText}", fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PreviewImage(card.frontPng)
                                PreviewImage(card.backPng)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm, enabled = enabled) { Text("确认恢复") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = enabled) { Text("取消") } },
    )
}

@Composable
private fun PreviewImage(bytes: ByteArray?) {
    val bitmap = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.width(110.dp).heightIn(max = 140.dp),
        )
    } else {
        Text("无正面图", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun LocalBackupSection(enabled: Boolean, onExport: () -> Unit, onImport: () -> Unit) {
    BackupSection(title = "本地备份", subtitle = "保留现有 ZIP 导入与导出") {
        BackupActionRow(
            icon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
            title = "导出全部数据",
            description = "包含卡组、卡片、复习状态和图片",
            buttonText = "选择位置",
            enabled = enabled,
            onClick = onExport,
        )
        BackupActionRow(
            icon = { Icon(Icons.Outlined.Unarchive, contentDescription = null) },
            title = "从文件恢复",
            description = "校验完整后导入为新的本地副本",
            buttonText = "选择文件",
            enabled = enabled,
            onClick = onImport,
        )
    }
}

@Composable
private fun RecentBackupSection(event: String?) {
    BackupSection(title = "最近记录") {
        Text(
            event ?: "尚无本次运行的云端操作记录",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BackupSection(
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                trailing()
            }
            content()
        }
    }
}

@Composable
private fun BackupActionRow(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    buttonText: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(buttonText) }
    }
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))
