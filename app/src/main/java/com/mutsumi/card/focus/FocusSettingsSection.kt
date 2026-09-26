package com.mutsumi.card.focus

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mutsumi.card.ui.components.FeedbackController

@Composable
fun FocusSettingsSection(controller: FocusReporterController, feedback: FeedbackController) {
    val state by controller.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var subjectsExpanded by remember { mutableStateOf(false) }
    var itemsExpanded by remember { mutableStateOf(false) }
    val catalog = state.catalog
    val selectedSubject = catalog?.subjects?.firstOrNull { it.id == state.configuration.subjectId }
    val selectedItem = catalog?.items?.firstOrNull { it.id == state.configuration.itemId && it.subjectId == state.configuration.subjectId }
    LaunchedEffect(state.hasKey) { if (state.hasKey && catalog == null) controller.refreshCatalog() }
    LaunchedEffect(state.message) {
        if (state.message.contains("失败") || state.message.startsWith("409")) feedback.show(state.message)
    }

    Surface(
        modifier = Modifier.fillMaxWidth().testTag("focus-reporter-settings"),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("408 Dashboard 专注上报", style = MaterialTheme.typography.titleMedium)
            Text("仅在学习页有卡片且应用位于前台时上报；离开后立即停止。", style = MaterialTheme.typography.bodySmall)
            Text(if (state.hasKey) "目录密钥已在本机加密保存" else "粘贴目录链接或密钥以读取科目和事项", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                label = { Text("目录链接或密钥") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("focus-reporter-key"),
            )
            Button(
                onClick = { val candidate = input; input = ""; controller.connect(candidate) },
                enabled = input.isNotBlank() && !state.loading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("focus-reporter-connect"),
            ) { Text(if (state.loading) "正在读取目录…" else "保存密钥并读取目录") }
            if (catalog != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("科目", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { subjectsExpanded = true }, modifier = Modifier.weight(2f).testTag("focus-reporter-subject")) {
                        Text(selectedSubject?.name ?: "选择科目")
                    }
                    DropdownMenu(expanded = subjectsExpanded, onDismissRequest = { subjectsExpanded = false }) {
                        catalog.subjects.forEach { subject ->
                            DropdownMenuItem(text = { Text("${subject.name} · ${subject.id}") }, onClick = {
                                subjectsExpanded = false; controller.selectSubject(subject.id)
                            })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("事项", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { itemsExpanded = true }, modifier = Modifier.weight(2f).testTag("focus-reporter-item")) {
                        Text(selectedItem?.name ?: "选择事项")
                    }
                    DropdownMenu(expanded = itemsExpanded, onDismissRequest = { itemsExpanded = false }) {
                        catalog.itemsForSubject(state.configuration.subjectId ?: -1).forEach { item ->
                            DropdownMenuItem(text = { Text("${item.name} · ${item.id}") }, onClick = {
                                itemsExpanded = false; controller.selectItem(item.id)
                            })
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("启用专注上报", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = state.configuration.enabled,
                    onCheckedChange = controller::setEnabled,
                    enabled = state.hasKey && selectedItem != null && !state.loading,
                    modifier = Modifier.testTag("focus-reporter-enabled"),
                )
            }
            Text(state.message, style = MaterialTheme.typography.bodySmall,
                color = if (state.message.startsWith("409") || state.message.contains("失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("focus-reporter-status"))
        }
    }
}
