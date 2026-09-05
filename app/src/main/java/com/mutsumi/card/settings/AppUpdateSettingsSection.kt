package com.mutsumi.card.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mutsumi.card.ui.components.FeedbackController

@Composable
fun AppUpdateSettingsSection(
    viewModel: AppUpdateViewModel,
    feedback: FeedbackController,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { feedback.show(it) }
    }

    Surface(
        modifier = modifier.fillMaxWidth().testTag("app-update-settings"),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("应用更新", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "仅向 GitHub 查询公开 Release；下载完成后仍由 Android 系统确认安装。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            UpdateSwitchRow(
                title = "自动检查更新",
                description = "启动应用时最多每 12 小时检查一次",
                checked = state.automaticCheckEnabled,
                onCheckedChange = viewModel::setAutomaticCheckEnabled,
                enabled = !state.isChecking,
                modifier = Modifier.testTag("automatic-update-check"),
            )
            UpdateSwitchRow(
                title = "自动下载更新",
                description = "发现新版后自动交给系统下载器；安装仍需系统确认",
                checked = state.automaticDownloadEnabled,
                onCheckedChange = viewModel::setAutomaticDownloadEnabled,
                enabled = state.automaticCheckEnabled && !state.isChecking,
                modifier = Modifier.testTag("automatic-update-download"),
            )
            OutlinedButton(
                onClick = viewModel::checkNow,
                enabled = !state.isChecking,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("check-app-update"),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.isChecking) "正在检查…" else "立即检查更新")
            }
            Text(
                state.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.errorMessage == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            state.availableUpdate?.let { update ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("发现新版本 ${update.versionName}", fontWeight = FontWeight.Bold)
                        if (update.releaseNotes.isNotBlank()) {
                            Text(
                                update.releaseNotes,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Button(
                            onClick = viewModel::requestDownload,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .testTag("download-app-update"),
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("下载并安装 ${update.versionName}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}
