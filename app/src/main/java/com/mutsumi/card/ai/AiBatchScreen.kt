package com.mutsumi.card.ai

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.graphics.BitmapFactory

@Composable
fun AiBatchScreen(viewModel: AiBatchViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showCreateDeck by remember { mutableStateOf(false) }
    var newDeckName by remember { mutableStateOf("") }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        runCatching { uris.map { readDocument(context, it) } }
            .onSuccess(viewModel::setImportedFiles)
            .onFailure { viewModel.showMessage("文件读取失败：${it.message}") }
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { readTree(context, DocumentFile.fromTreeUri(context, uri) ?: error("无法打开文件夹")) }
                .onSuccess(viewModel::setImportedFiles)
                .onFailure { viewModel.showMessage("文件夹读取失败：${it.message}") }
        }
    }
    Column(modifier = modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AiTopBar(
            state = state,
            onFiles = { fileLauncher.launch(arrayOf("text/plain", "text/markdown", "text/*")) },
            onFolder = { folderLauncher.launch(null) },
            onGenerate = viewModel::generate,
            onParameters = viewModel::setParameters,
            onCreateDeck = { showCreateDeck = true },
        )
        state.message.takeIf { it.isNotBlank() }?.let { Text(it, fontWeight = FontWeight.Bold) }
        state.contextWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            if (maxWidth >= 700.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SourcePane(state, viewModel, Modifier.weight(1f))
                    CandidatePane(state, viewModel, Modifier.weight(1f))
                }
            } else {
                var tab by remember { mutableStateOf(0) }
                Column(Modifier.fillMaxSize()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { tab = 0 }) { Text("素材") }
                        TextButton(onClick = { tab = 1 }) { Text("候选") }
                    }
                    Box(Modifier.weight(1f)) {
                        if (tab == 0) SourcePane(state, viewModel, Modifier.fillMaxSize())
                        else CandidatePane(state, viewModel, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
    if (showCreateDeck) {
        AlertDialog(
            onDismissRequest = { showCreateDeck = false },
            title = { Text("新建卡组") },
            text = {
                OutlinedTextField(
                    value = newDeckName,
                    onValueChange = { newDeckName = it },
                    label = { Text("卡组名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.createDeck(newDeckName)
                        newDeckName = ""
                        showCreateDeck = false
                    },
                    enabled = newDeckName.isNotBlank(),
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreateDeck = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun AiTopBar(
    state: AiBatchUiState,
    onFiles: () -> Unit,
    onFolder: () -> Unit,
    onGenerate: () -> Unit,
    onParameters: (AiGenerationParameters) -> Unit,
    onCreateDeck: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.parameters.groupCount.toString(),
            onValueChange = { it.toIntOrNull()?.let { value -> onParameters(state.parameters.copy(groupCount = value.coerceAtLeast(1))) } },
            label = { Text("卡组数") },
            singleLine = true,
            modifier = Modifier.width(92.dp),
        )
        OutlinedTextField(
            value = state.parameters.candidatesPerGroup.toString(),
            onValueChange = { it.toIntOrNull()?.let { value -> onParameters(state.parameters.copy(candidatesPerGroup = value.coerceAtLeast(1))) } },
            label = { Text("每组候选") },
            singleLine = true,
            modifier = Modifier.width(110.dp),
        )
        DeckSelector(state, onParameters, onCreateDeck)
        OutlinedButton(onClick = onFiles) { Text("导入文件") }
        OutlinedButton(onClick = onFolder) { Text("导入文件夹") }
        Button(onClick = onGenerate, enabled = !state.isGenerating && !state.isSaving) { Text("开始生成") }
    }
}

@Composable
private fun DeckSelector(
    state: AiBatchUiState,
    onParameters: (AiGenerationParameters) -> Unit,
    onCreateDeck: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(state.decks.firstOrNull { it.id == state.parameters.targetDeckId }?.name ?: "选择卡组") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.decks.forEach { deck ->
                DropdownMenuItem(
                    text = { Text(deck.name) },
                    onClick = { onParameters(state.parameters.copy(targetDeckId = deck.id)); expanded = false },
                )
            }
            DropdownMenuItem(
                text = { Text("新建卡组") },
                onClick = { expanded = false; onCreateDeck() },
            )
        }
    }
}

@Composable
private fun SourcePane(state: AiBatchUiState, viewModel: AiBatchViewModel, modifier: Modifier) {
    Surface(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("原始素材", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = state.rawText,
                onValueChange = viewModel::setRawText,
                modifier = Modifier.fillMaxWidth().weight(1f),
                placeholder = { Text("粘贴素材或从上方导入文件") },
            )
        }
    }
}

@Composable
private fun CandidatePane(state: AiBatchUiState, viewModel: AiBatchViewModel, modifier: Modifier) {
    Surface(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("候选卡片", fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.groups.forEachIndexed { index, _ ->
                    OutlinedButton(onClick = { viewModel.selectGroup(index) }) { Text("第 ${index + 1} 组") }
                }
            }
            val group = state.groups.getOrNull(state.groupIndex)
            if (group == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { Text("生成后将在这里追加候选") }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(group.cards) { index, card ->
                        CandidateCard(card, index == state.selectedCardIndex) { viewModel.selectCard(index) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::previousGroup, enabled = state.groupIndex > 0) { Text("上一组") }
                    OutlinedButton(onClick = viewModel::nextGroup, enabled = state.groupIndex < state.groups.lastIndex) { Text("下一组") }
                    Button(onClick = viewModel::saveCurrentAndNext, enabled = !state.isSaving) { Text("保存当前并下一组") }
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(card: AiCardCandidate, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        border = BorderStroke(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(card.keyText, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PngPreview(card.frontPng, Modifier.weight(1f))
                PngPreview(card.backPng, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PngPreview(bytes: ByteArray, modifier: Modifier) {
    val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
    if (bitmap != null) Image(bitmap.asImageBitmap(), null, modifier.height(190.dp))
}

private fun readDocument(context: android.content.Context, uri: Uri): ImportedAiFile {
    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "未命名.txt"
    require(name.substringAfterLast('.', "txt").lowercase() in setOf("txt", "md", "markdown")) { "不支持的文件类型：$name" }
    val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        ?: error("无法读取文件：$name")
    return ImportedAiFile(name, content)
}

private fun readTree(context: android.content.Context, root: DocumentFile): List<ImportedAiFile> {
    val result = mutableListOf<ImportedAiFile>()
    fun visit(node: DocumentFile, prefix: String) {
        node.listFiles().sortedBy { it.name.orEmpty() }.forEach { child ->
            val name = child.name ?: return@forEach
            if (child.isDirectory) visit(child, "$prefix$name/")
            else if (name.substringAfterLast('.', "txt").lowercase() in setOf("txt", "md", "markdown")) {
                val content = context.contentResolver.openInputStream(child.uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("无法读取文件：$prefix$name")
                result += ImportedAiFile("$prefix$name", content)
            }
        }
    }
    visit(root, "")
    return result
}
