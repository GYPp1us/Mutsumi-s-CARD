package com.mutsumi.card.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MergeType
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mutsumi.card.draw.DrawingCanvasSpec
import com.mutsumi.card.ui.components.FeedbackController
import com.mutsumi.card.ui.theme.AssistBlue
import com.mutsumi.card.ui.theme.PrimaryGreenSoft
import com.mutsumi.card.ui.theme.WorkspaceBackground
import kotlinx.coroutines.launch

private const val MANUAL_SOURCE_ID = "manual-source"

/** 横屏 IME 期间优先保留手动素材面板，避免工作流分栏被压缩到不可操作。 */
internal fun useImeSourceLayout(width: Dp, height: Dp, imeVisible: Boolean): Boolean =
    imeVisible && width >= 700.dp && width > height

/** 素材选择 → 拆分/拼接节点 → 成品候选的批量录入工作台。 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun AiBatchScreen(
    viewModel: AiBatchViewModel,
    feedback: FeedbackController,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var showCreateDeck by remember { mutableStateOf(false) }
    var newDeckName by remember { mutableStateOf("") }

    LaunchedEffect(state.errorMessage) { state.errorMessage?.let { feedback.show(it) } }

    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.importDocuments(uris)
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            viewModel.importTree(uri)
        } else {
            viewModel.showMessage("已取消选择素材文件夹")
        }
    }

    CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)) {
        BoxWithConstraints(modifier = modifier.fillMaxSize().padding(12.dp)) {
            val prioritizeManualSource = useImeSourceLayout(maxWidth, maxHeight, WindowInsets.isImeVisible)
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!prioritizeManualSource) {
                    WorkflowTopBar(
                        state = state,
                        onFiles = { documentPicker.launch(arrayOf("text/plain", "text/markdown", "text/*")) },
                        onFolder = { treePicker.launch(null) },
                        onGenerate = viewModel::generate,
                        onParameters = viewModel::setParameters,
                        onCreateDeck = { showCreateDeck = true },
                        onCopyMessage = { message ->
                            clipboard.setText(AnnotatedString(message))
                            scope.launch { feedback.show("已复制通知内容") }
                        },
                    )
                    state.contextWarning?.let { WorkflowNotice(it, true) }
                    if (state.isImporting) WorkflowNotice("正在后台读取素材，读取完成后会自动加入工作流。", false)
                }
                BoxWithConstraints(modifier = Modifier.weight(1f)) {
                    when {
                        maxWidth >= 1_040.dp -> WideWorkflowLayout(state, viewModel, prioritizeManualSource)
                        maxWidth >= 700.dp -> MediumWorkflowLayout(state, viewModel, prioritizeManualSource)
                        else -> CompactWorkflowLayout(state, viewModel)
                    }
                }
            }
        }
    }

    if (showCreateDeck) {
        AlertDialog(
            onDismissRequest = { showCreateDeck = false },
            title = { Text("新建目标卡组") },
            text = {
                OutlinedTextField(
                    value = newDeckName,
                    onValueChange = { newDeckName = it },
                    label = { Text("卡组名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
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
                ) {
                    Icon(Icons.Outlined.CreateNewFolder, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("创建卡组")
                }
            },
            dismissButton = { OutlinedButton(onClick = { showCreateDeck = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun WorkflowTopBar(
    state: AiBatchUiState,
    onFiles: () -> Unit,
    onFolder: () -> Unit,
    onGenerate: () -> Unit,
    onParameters: (AiGenerationParameters) -> Unit,
    onCreateDeck: () -> Unit,
    onCopyMessage: (String) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val controls: @Composable () -> Unit = {
            ParameterMenu(
                label = "卡片组",
                selectedLabel = state.parameters.groupCountRange.label,
                options = AiGroupCountRange.entries.toList(),
                optionLabel = AiGroupCountRange::label,
                onSelect = { onParameters(state.parameters.copy(groupCountRange = it)) },
            )
            ParameterMenu(
                label = "每组候选",
                selectedLabel = state.parameters.candidatesPerGroup.toString(),
                options = (1..5).toList(),
                optionLabel = Int::toString,
                onSelect = { onParameters(state.parameters.copy(candidatesPerGroup = it)) },
            )
            DeckMenu(state, onParameters, onCreateDeck)
        }
        if (maxWidth < 620.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AI 批量工作流", style = MaterialTheme.typography.titleLarge)
                controls()
                WorkflowActions(state.isImporting, state.isGenerating, state.isSaving, onFiles, onFolder, onGenerate)
                state.message.takeIf(String::isNotBlank)?.let { WorkflowNotification(it, state.errorMessage != null, onCopyMessage) }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("AI 批量工作流", style = MaterialTheme.typography.titleLarge)
                controls()
                WorkflowActions(state.isImporting, state.isGenerating, state.isSaving, onFiles, onFolder, onGenerate)
                state.message.takeIf(String::isNotBlank)?.let { WorkflowNotification(it, state.errorMessage != null, onCopyMessage) }
            }
        }
    }
}

@Composable
private fun WorkflowActions(
    isImporting: Boolean,
    isGenerating: Boolean,
    isSaving: Boolean,
    onFiles: () -> Unit,
    onFolder: () -> Unit,
    onGenerate: () -> Unit,
) {
    val isBusy = isImporting || isGenerating || isSaving
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onFiles, enabled = !isBusy) { Icon(Icons.Outlined.UploadFile, contentDescription = "导入文本文件") }
        IconButton(onClick = onFolder, enabled = !isBusy) { Icon(Icons.Outlined.FolderOpen, contentDescription = "递归导入文件夹") }
        Button(onClick = onGenerate, enabled = !isBusy) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    isImporting -> "读取中"
                    isGenerating -> "生成中"
                    else -> "运行工作流"
                },
            )
        }
    }
}

@Composable
private fun WorkflowNotification(message: String, isError: Boolean, onCopy: (String) -> Unit) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable { onCopy(message) }.testTag("ai-workflow-notification"),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = "复制完整通知", modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(message, fontSize = 12.sp, maxLines = 2)
        }
    }
}

@Composable
private fun <T> ParameterMenu(
    label: String,
    selectedLabel: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.widthIn(min = 112.dp, max = 168.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(selectedLabel, maxLines = 1) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = {
                        onSelect(option)
                        expanded = false
                    })
                }
            }
        }
    }
}

@Composable
private fun DeckMenu(
    state: AiBatchUiState,
    onParameters: (AiGenerationParameters) -> Unit,
    onCreateDeck: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.widthIn(min = 160.dp, max = 220.dp)) {
        Text("目标卡组", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(state.decks.firstOrNull { it.id == state.parameters.targetDeckId }?.name ?: "选择卡组", maxLines = 1)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.decks.forEach { deck ->
                    DropdownMenuItem(text = { Text(deck.name) }, onClick = {
                        onParameters(state.parameters.copy(targetDeckId = deck.id))
                        expanded = false
                    })
                }
                DropdownMenuItem(
                    text = { Text("新建卡组") },
                    leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                    onClick = { expanded = false; onCreateDeck() },
                )
            }
        }
    }
}

@Composable
private fun WideWorkflowLayout(
    state: AiBatchUiState,
    viewModel: AiBatchViewModel,
    prioritizeManualSource: Boolean,
) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SourcePane(state, viewModel, Modifier.weight(0.95f).fillMaxHeight())
        if (!prioritizeManualSource) {
            WorkflowPane(state, viewModel, Modifier.weight(1.1f).fillMaxHeight())
            ResultPane(state, viewModel, Modifier.weight(1.15f).fillMaxHeight())
        }
    }
}

@Composable
private fun MediumWorkflowLayout(
    state: AiBatchUiState,
    viewModel: AiBatchViewModel,
    prioritizeManualSource: Boolean,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!prioritizeManualSource) {
            WorkflowPane(state, viewModel, Modifier.fillMaxWidth().weight(0.85f))
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SourcePane(state, viewModel, Modifier.weight(1f).fillMaxHeight())
            if (!prioritizeManualSource) {
                ResultPane(state, viewModel, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun CompactWorkflowLayout(state: AiBatchUiState, viewModel: AiBatchViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            WorkflowTab("素材", Icons.Outlined.FolderOpen, tab == 0) { tab = 0 }
            WorkflowTab("工作流", Icons.Outlined.CallSplit, tab == 1) { tab = 1 }
            WorkflowTab("结果", Icons.Outlined.AutoAwesome, tab == 2) { tab = 2 }
        }
        when (tab) {
            0 -> SourcePane(state, viewModel, Modifier.fillMaxSize())
            1 -> WorkflowPane(state, viewModel, Modifier.fillMaxSize())
            else -> ResultPane(state, viewModel, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun WorkflowTab(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Tab(selected = selected, onClick = onClick, text = { Text(label) }, icon = { Icon(icon, contentDescription = label) })
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SourcePane(state: AiBatchUiState, viewModel: AiBatchViewModel, modifier: Modifier) {
    val manualSourceBringIntoViewRequester = remember { BringIntoViewRequester() }
    var manualSourceFocused by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible

    LaunchedEffect(manualSourceFocused, imeVisible) {
        if (manualSourceFocused && imeVisible) {
            withFrameNanos { }
            manualSourceBringIntoViewRequester.bringIntoView()
        }
    }

    WorkflowSurface(modifier.testTag("ai-source-pane")) {
        Column(Modifier.fillMaxSize()) {
            PaneTitle(Icons.Outlined.FolderOpen, "① 素材选择", "已连接 ${state.workflowSummary.selectedSourceCount} 份素材")
            HorizontalDivider()
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = 4.dp).testTag("ai-source-scroll"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (state.isImporting) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("正在后台读取素材", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    Text(
                        "勾选进入工作流的笔记；文件夹会递归读取 .txt、.md、.markdown。最多缓存 500 份、合计 100 万字符，超出项会列在下方。",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.files.isEmpty()) {
                    item { EmptyState("还没有导入笔记", "使用顶栏的文件或文件夹图标添加素材。") }
                } else {
                    items(state.files, key = ImportedAiFile::id) { file ->
                        SourceFileRow(
                            file = file,
                            selected = file.id in state.workflow.selectedSourceIds,
                            onSelected = { viewModel.setSourceSelected(file.id, it) },
                            onRemove = { viewModel.removeSource(file.id) },
                        )
                    }
                }
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("手动补充素材", modifier = Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = state.rawText,
                        onValueChange = viewModel::setRawText,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp, max = 196.dp)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .bringIntoViewRequester(manualSourceBringIntoViewRequester)
                            .onFocusChanged { manualSourceFocused = it.isFocused }
                            .testTag("ai-manual-source"),
                        minLines = 3,
                        maxLines = 8,
                        label = { Text("可与文件素材自由拼接") },
                        placeholder = { Text("粘贴摘录、题纲或临时说明；输入后会自动连接。") },
                    )
                    if (state.rawText.isNotBlank()) {
                        SourceToggleRow(
                            selected = MANUAL_SOURCE_ID in state.workflow.selectedSourceIds,
                            onSelected = { viewModel.setSourceSelected(MANUAL_SOURCE_ID, it) },
                        )
                    }
                    state.manualSourceWarning?.let { WorkflowNotice(it, true) }
                }
                if (state.sourceImportIssues.isNotEmpty()) item { ImportIssues(state.sourceImportIssues) }
            }
        }
    }
}

@Composable
private fun SourceFileRow(file: ImportedAiFile, selected: Boolean, onSelected: (Boolean) -> Unit, onRemove: () -> Unit) {
    Surface(
        color = if (selected) PrimaryGreenSoft else Color.Transparent,
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(7.dp))
            .clickable { onSelected(!selected) },
    ) {
        Row(Modifier.padding(start = 2.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = onSelected)
            Column(Modifier.weight(1f)) {
                Text(file.name, maxLines = 2, fontWeight = FontWeight.SemiBold)
                Text("${file.content.length} 个字符", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRemove) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "移除素材：${file.name}") }
        }
    }
}

@Composable
private fun SourceToggleRow(selected: Boolean, onSelected: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).clickable { onSelected(!selected) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = onSelected)
        Column {
            Text("手动补充素材")
            Text("可随时断开或重新接入", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ImportIssues(issues: List<AiSourceImportIssue>) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier.fillMaxWidth().padding(8.dp),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("以下素材未导入", fontWeight = FontWeight.Bold)
            issues.forEach { Text(it.displayText, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun WorkflowPane(state: AiBatchUiState, viewModel: AiBatchViewModel, modifier: Modifier) {
    WorkflowSurface(modifier.testTag("ai-workflow-pane"), workspace = true) {
        LazyColumn(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { PaneTitle(Icons.Outlined.CallSplit, "② 工作流拼接", "节点配置会直接改变 AI 请求材料") }
            item {
                WorkflowNode("01", Icons.Outlined.FolderOpen, "素材选择", "${state.workflowSummary.selectedSourceCount} 份已连接素材") {
                    Text("从左侧勾选笔记或手动素材；未连接的内容不会进入请求。")
                }
            }
            item { WorkflowConnector() }
            item {
                WorkflowNode("02", Icons.Outlined.CallSplit, "拆分规则", "预计 ${state.workflowSummary.segmentCount} 个片段") {
                    AiSplitMode.entries.forEach { mode ->
                        WorkflowChoice(mode.label, mode.description, state.workflow.splitMode == mode) {
                            viewModel.setWorkflow(state.workflow.copy(splitMode = mode))
                        }
                    }
                }
            }
            item { WorkflowConnector() }
            item {
                WorkflowNode("03", Icons.Outlined.MergeType, "拼接规则", "组合为 ${state.workflowSummary.materialBundleCount} 个材料组") {
                    AiCombineMode.entries.forEach { mode ->
                        WorkflowChoice(mode.label, mode.description, state.workflow.combineMode == mode) {
                            viewModel.setWorkflow(state.workflow.copy(combineMode = mode))
                        }
                    }
                    if (state.workflow.combineMode == AiCombineMode.Adjacent) {
                        CombineSizeControl(state.workflow.combineSize) { value ->
                            viewModel.setWorkflow(state.workflow.copy(combineSize = value))
                        }
                    }
                }
            }
            item { WorkflowConnector() }
            item {
                WorkflowNode(
                    "04",
                    Icons.Outlined.AutoAwesome,
                    "生成任务",
                    "${state.parameters.groupCountRange.label} 组 · 每组 ${state.parameters.candidatesPerGroup} 个候选",
                ) {
                    Text("运行后只注册 generate_card_group；候选按 4 秒节流追加到右侧队列。")
                }
            }
        }
    }
}

@Composable
private fun WorkflowNode(
    ordinal: String,
    icon: ImageVector,
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            Surface(color = AssistBlue, contentColor = MaterialTheme.colorScheme.onTertiary, shape = RoundedCornerShape(6.dp), modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(ordinal, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(title, fontWeight = FontWeight.Bold)
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                content()
            }
        }
    }
}

@Composable
private fun WorkflowConnector() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.width(2.dp).height(12.dp).background(MaterialTheme.colorScheme.outline))
        Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun WorkflowChoice(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            if (selected) Text("已连接", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CombineSizeControl(value: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("每个材料组", style = MaterialTheme.typography.bodySmall)
        IconButton(onClick = { onChange((value - 1).coerceAtLeast(2)) }) {
            Icon(Icons.Outlined.ChevronLeft, contentDescription = "减少每组片段数")
        }
        Text("$value 个片段", fontWeight = FontWeight.Bold)
        IconButton(onClick = { onChange((value + 1).coerceAtMost(8)) }) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = "增加每组片段数")
        }
    }
}

@Composable
private fun ResultPane(state: AiBatchUiState, viewModel: AiBatchViewModel, modifier: Modifier) {
    WorkflowSurface(modifier.testTag("ai-result-pane")) {
        Column(Modifier.fillMaxSize()) {
            PaneTitle(
                Icons.Outlined.AutoAwesome,
                "③ 结果预览",
                "${state.groups.size}/${AiCandidateQueue.MAX_PENDING_GROUPS} 组候选已进入队列",
            )
            HorizontalDivider()
            if (state.isWaitingForCandidateCapacity) {
                WorkflowNotice("候选队列已满；保存当前选中的一组后会继续生成。", false)
            }
            val group = state.groups.getOrNull(state.groupIndex)
            if (group == null) {
                EmptyState("等待工作流输出", "运行后，候选卡片会按生成顺序追加到这里。", Modifier.weight(1f))
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(state.groups, key = { _, item -> item.index }) { index, item ->
                        FilterChip(selected = index == state.groupIndex, onClick = { viewModel.selectGroup(index) }, label = { Text("组 ${item.index}") })
                    }
                }
                LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(group.cards, key = { index, _ -> candidatePreviewItemKey(group.index, index) }) { index, card ->
                        CandidateCard(card, index == state.selectedCardIndex) { viewModel.selectCard(index) }
                    }
                }
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = viewModel::previousGroup, enabled = state.groupIndex > 0) {
                            Icon(Icons.Outlined.ChevronLeft, contentDescription = "上一候选组")
                        }
                        IconButton(onClick = viewModel::nextGroup, enabled = state.groupIndex < state.groups.lastIndex) {
                            Icon(Icons.Outlined.ChevronRight, contentDescription = "下一候选组")
                        }
                        Text("第 ${state.groupIndex + 1} / ${state.groups.size} 组", modifier = Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = viewModel::saveCurrentAndNext, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Save, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.isSaving) "保存中" else "保存当前卡片并继续")
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(card: AiCardCandidate, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(card.keyText, fontWeight = FontWeight.Bold, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PngPreview(card.frontPng, "正面预览", Modifier.weight(1f))
                PngPreview(card.backPng, "背面预览", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PngPreview(bytes: ByteArray, description: String, modifier: Modifier) {
    val bitmap = remember(bytes) { decodePreviewBitmap(bytes) }
    DisposableEffect(bitmap) {
        onDispose { bitmap?.recycle() }
    }
    val image = bitmap?.asImageBitmap()
    if (image == null) {
        Box(modifier.aspectRatio(DrawingCanvasSpec.aspectRatio).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text("预览不可用", style = MaterialTheme.typography.labelSmall)
        }
    } else {
        Image(
            bitmap = image,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = modifier.aspectRatio(DrawingCanvasSpec.aspectRatio).background(Color.White),
        )
    }
}

/** 候选预览只保留适合侧栏显示的下采样位图，避免一次解码多张完整卡面。 */
private fun decodePreviewBitmap(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > 320 || bounds.outHeight / sampleSize > 480) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    )
}

@Composable
private fun WorkflowSurface(modifier: Modifier, workspace: Boolean = false, content: @Composable () -> Unit) {
    Surface(
        color = if (workspace) WorkspaceBackground else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
        content = content,
    )
}

@Composable
private fun PaneTitle(icon: ImageVector, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyState(title: String, detail: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WorkflowNotice(text: String, isError: Boolean) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
    }
}
