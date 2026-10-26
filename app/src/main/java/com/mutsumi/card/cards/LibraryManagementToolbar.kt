package com.mutsumi.card.cards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mutsumi.card.data.LibraryAction

@Composable
internal fun LibraryManagementToolbar(state: CardsUiState, callbacks: CardsCallbacks) {
    var targetAction by remember { mutableStateOf<LibraryAction?>(null) }
    val selected = state.selection
    val count = selected.cardIds.size + selected.deckIds.size
    Column(Modifier.fillMaxWidth().testTag("library-management-toolbar")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                TextButton(callbacks.onBrowseRoot, enabled = !state.isBusy) { Text("全部卡组") }
                LibraryTree.breadcrumbs(state.decks, state.currentDeck?.id).forEach { deck ->
                    Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp))
                    TextButton({ callbacks.onSwitchDeck(deck.id) }, enabled = !state.isBusy) { Text(deck.name, maxLines = 1) }
                }
            }
            TextButton(callbacks.onToggleArchived, enabled = !state.isBusy) { Text(if (state.showArchived) "隐藏归档" else "显示归档") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            TextButton(callbacks.onSelectAll, enabled = !state.isBusy) { Text("全选") }
            if (count > 0) {
                Text("已选 $count", style = MaterialTheme.typography.labelSmall)
                TextButton(callbacks.onClearSelection, enabled = !state.isBusy) { Text("取消选择") }
                listOf(LibraryAction.Move, LibraryAction.Copy).forEach { action ->
                    TextButton({ targetAction = action }, enabled = !state.isBusy) { Text(action.label) }
                }
                listOf(LibraryAction.Archive, LibraryAction.Restore).forEach { action ->
                    TextButton({ callbacks.onManage(action, null) }, enabled = !state.isBusy) { Text(action.label) }
                }
                TextButton(callbacks.onPreviewDelete, enabled = !state.isBusy, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") }
            } else {
                Text("勾选卡组或卡片进行批量管理", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    targetAction?.let { action ->
        val excluded = LibraryTree.descendants(state.decks, selected.deckIds)
        val destinations = state.decks.filter { it.id !in excluded }
        var target by rememberSaveable(action) { mutableStateOf<Long?>(null) }
        AlertDialog(onDismissRequest = { targetAction = null }, title = { Text("${action.label}到卡组") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("library-destination-list")) {
                    if (selected.cardIds.isEmpty()) item {
                        Row(Modifier.fillMaxWidth().clickable { target = null }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(target == null, { target = null }); Text("全部卡组（根目录）")
                        }
                    }
                    items(destinations, key = { it.id }) { deck ->
                        Row(Modifier.fillMaxWidth().testTag("destination-${deck.id}").clickable { target = deck.id }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(target == deck.id, { target = deck.id })
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text(deck.name)
                                Text(LibraryTree.breadcrumbs(state.decks, deck.id).joinToString(" / ") { it.name },
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (destinations.isEmpty() && selected.cardIds.isNotEmpty()) item { Text("请先新建一个目标卡组") }
                }
            },
            confirmButton = { TextButton({ callbacks.onManage(action, target); targetAction = null },
                enabled = !state.isBusy && (target != null || selected.cardIds.isEmpty())) { Text("确认${action.label}") } },
            dismissButton = { TextButton({ targetAction = null }) { Text("取消") } })
    }
}
