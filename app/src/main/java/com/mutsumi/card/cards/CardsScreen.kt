package com.mutsumi.card.cards

import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutsumi.card.domain.model.Deck
import com.mutsumi.card.domain.model.MemoryCard
import com.mutsumi.card.data.image.CardImageStore
import com.mutsumi.card.draw.DrawingCanvasSpec
import com.mutsumi.card.ui.adaptive.AppLayoutMode
import com.mutsumi.card.ui.theme.DangerCoral
import com.mutsumi.card.ui.theme.Divider
import com.mutsumi.card.ui.theme.PrimaryGreen
import com.mutsumi.card.ui.theme.PrimaryGreenSoft
import com.mutsumi.card.ui.theme.StrongDivider
import java.text.DateFormat
import java.util.Date
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

typealias CardImageContent = @Composable (MemoryCard, Modifier) -> Unit

data class CardsCallbacks(
    val onQueryChange: (String) -> Unit,
    val onClearQuery: () -> Unit,
    val onSelectCard: (Long?) -> Unit,
    val onSwitchDeck: (Long) -> Unit,
    val onCreateDeck: (String) -> Unit,
    val onRenameDeck: (String) -> Unit,
    val onNewCard: () -> Unit,
    val onSaveKey: (String) -> Unit,
    val onRedraw: () -> Unit,
    val onArchive: () -> Unit,
    val onDelete: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardsScreen(
    uiState: CardsUiState,
    layoutMode: AppLayoutMode,
    imageContent: CardImageContent,
    callbacks: CardsCallbacks,
    modifier: Modifier = Modifier,
) {
    var deckMenuOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<DeckDialog?>(null) }
    val selectedCard = uiState.selectedCard

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CardsTopBar(
            state = uiState,
            enabled = !uiState.isBusy,
            onOpenDecks = { deckMenuOpen = true },
            onCreateDeck = { dialog = DeckDialog.Create },
            onNewCard = callbacks.onNewCard,
        )
        if (uiState.isBusy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("卡片操作进行中"))
        }
        Box {
            DropdownMenu(expanded = deckMenuOpen, onDismissRequest = { deckMenuOpen = false }) {
                uiState.decks.forEach { deck ->
                    DropdownMenuItem(
                        text = { Text(deck.name) },
                        enabled = !uiState.isBusy,
                        onClick = {
                            deckMenuOpen = false
                            callbacks.onSwitchDeck(deck.id)
                        },
                        trailingIcon = if (deck.id == uiState.currentDeck?.id) {
                            { Text("当前", color = PrimaryGreen, fontSize = 11.sp) }
                        } else null,
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("重命名当前卡组") },
                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                    enabled = uiState.currentDeck != null && !uiState.isBusy,
                    onClick = {
                        deckMenuOpen = false
                        dialog = DeckDialog.Rename(uiState.currentDeck?.name.orEmpty())
                    },
                )
            }
        }
        SearchField(uiState.query, callbacks.onQueryChange, callbacks.onClearQuery, !uiState.isBusy)
        when {
            uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            uiState.cards.isEmpty() -> CardsEmptyState(
                hasQuery = uiState.query.isNotEmpty(),
                onClearQuery = callbacks.onClearQuery,
                onNewCard = callbacks.onNewCard,
                enabled = !uiState.isBusy,
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(uiState.cards, key = { it.id }) { card ->
                    CardListItem(
                        card = card,
                        selected = selectedCard?.id == card.id,
                        compact = layoutMode != AppLayoutMode.LandscapeThreePane,
                        imageContent = imageContent,
                        enabled = !uiState.isBusy,
                        onClick = { callbacks.onSelectCard(card.id) },
                    )
                }
            }
        }
    }

    if (layoutMode != AppLayoutMode.LandscapeThreePane && selectedCard != null) {
        ModalBottomSheet(
            onDismissRequest = { callbacks.onSelectCard(null) },
            modifier = Modifier.testTag("卡片详情弹层"),
        ) {
            CardsContextPane(
                card = selectedCard,
                imageContent = imageContent,
                keySaveRevision = uiState.keySaveRevision,
                isBusy = uiState.isBusy,
                compactHeight = false,
                onSaveKey = callbacks.onSaveKey,
                onRedraw = callbacks.onRedraw,
                onArchive = callbacks.onArchive,
                onDelete = callbacks.onDelete,
                modifier = Modifier.fillMaxWidth().heightIn(max = 580.dp),
            )
        }
    }

    dialog?.let { active ->
        NameDialog(
            title = if (active is DeckDialog.Create) "新建卡组" else "重命名卡组",
            initialValue = (active as? DeckDialog.Rename)?.name.orEmpty(),
            onDismiss = { dialog = null },
            onConfirm = { name ->
                dialog = null
                if (active is DeckDialog.Create) callbacks.onCreateDeck(name) else callbacks.onRenameDeck(name)
            },
        )
    }
}

@Composable
private fun CardsTopBar(
    state: CardsUiState,
    enabled: Boolean,
    onOpenDecks: () -> Unit,
    onCreateDeck: () -> Unit,
    onNewCard: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("卡片", style = MaterialTheme.typography.titleLarge, maxLines = 1)
            Text(
                "${state.currentDeck?.name ?: "暂无卡组"} · ${state.currentDeck?.cardCount ?: 0} 张",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = onOpenDecks, enabled = enabled) { Icon(Icons.Default.FolderOpen, "切换卡组") }
        }
        IconButton(onClick = onCreateDeck, enabled = enabled) { Icon(Icons.Default.CreateNewFolder, "新建卡组") }
        IconButton(onClick = onNewCard, enabled = state.currentDeck != null && enabled) {
            Icon(Icons.Default.Add, "录入卡片")
        }
    }
    HorizontalDivider(color = Divider)
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, onClear: () -> Unit, enabled: Boolean) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).height(48.dp),
        singleLine = true,
        enabled = enabled,
        placeholder = { Text("搜索文字 key") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = if (query.isNotEmpty()) {
            { IconButton(onClick = onClear) { Icon(Icons.Default.Clear, "清除搜索") } }
        } else null,
    )
}

@Composable
private fun CardListItem(
    card: MemoryCard,
    selected: Boolean,
    compact: Boolean,
    imageContent: CardImageContent,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val thumbnailWidth = if (compact) 36.dp else 42.dp
    val minHeight = if (compact) 76.dp else 96.dp
    Row(
        Modifier.fillMaxWidth().heightIn(min = minHeight)
            .background(if (selected) PrimaryGreenSoft.copy(alpha = 0.7f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(minHeight).background(if (selected) PrimaryGreen else Color.Transparent))
        Spacer(Modifier.width(9.dp))
        imageContent(
            card,
            Modifier.width(thumbnailWidth).aspectRatio(DrawingCanvasSpec.aspectRatio)
                .clip(MaterialTheme.shapes.extraSmall).border(1.dp, StrongDivider, MaterialTheme.shapes.extraSmall)
                .testTag("卡片缩略图-${card.id}"),
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(card.keyText, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(reviewSummary(card), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, "查看详情", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
    }
    HorizontalDivider(color = Divider)
}

@Composable
fun StoredCardValueImage(
    card: MemoryCard,
    imageStore: CardImageStore,
    modifier: Modifier = Modifier,
) {
    var targetSize by remember(card.valueImagePath) { mutableStateOf(IntSize.Zero) }
    val cacheKey = remember(card.valueImagePath, targetSize) {
        "${card.valueImagePath}:${targetSize.width}x${targetSize.height}"
    }
    val result by produceState<ImageLoadResult>(ImageLoadResult.Loading, cacheKey, imageStore) {
        if (targetSize == IntSize.Zero) return@produceState
        value = CardValueBitmapCache[cacheKey]?.let(ImageLoadResult::Ready) ?: loadCardImage(
            imageStore = imageStore,
            path = card.valueImagePath,
            targetSize = targetSize,
        ).also { loaded ->
            if (loaded is ImageLoadResult.Ready) CardValueBitmapCache.put(cacheKey, loaded.bitmap)
        }
    }
    val sizedModifier = modifier.onSizeChanged { if (it != IntSize.Zero) targetSize = it }
    when (val loaded = result) {
        ImageLoadResult.Loading -> Box(sizedModifier.background(MaterialTheme.colorScheme.surfaceVariant))
        ImageLoadResult.Missing -> Box(
            sizedModifier.background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.BrokenImage, "图片缺失", tint = MaterialTheme.colorScheme.error)
                Text("图片缺失", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
        }
        is ImageLoadResult.Ready -> Image(
            bitmap = loaded.bitmap,
            contentDescription = "图片 value：${card.keyText}",
            modifier = sizedModifier,
            contentScale = ContentScale.Fit,
        )
    }
}

private suspend fun loadCardImage(
    imageStore: CardImageStore,
    path: String,
    targetSize: IntSize,
): ImageLoadResult = withContext(Dispatchers.IO) {
    val file = imageStore.resolve(path)
    if (!file.isFile) return@withContext ImageLoadResult.Missing
    try {
        val bytes = imageStore.read(path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext ImageLoadResult.Missing
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetSize.width, targetSize.height)
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?.asImageBitmap()
            ?.let(ImageLoadResult::Ready)
            ?: ImageLoadResult.Missing
    } catch (error: IOException) {
        Log.e("MutsumiCard", "读取卡片图片失败：$path", error)
        ImageLoadResult.Missing
    }
}

internal fun calculateInSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): Int {
    if (targetWidth <= 0 || targetHeight <= 0) return 1
    var sample = 1
    while (sourceWidth / (sample * 2) >= targetWidth && sourceHeight / (sample * 2) >= targetHeight) {
        sample *= 2
    }
    return sample
}

/** Process-lifetime cache, bounded to 12 MiB and invalidated naturally when the process exits. */
private object CardValueBitmapCache : LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: androidx.compose.ui.graphics.ImageBitmap): Int =
        value.width * value.height * 4
}

private sealed interface ImageLoadResult {
    data object Loading : ImageLoadResult
    data object Missing : ImageLoadResult
    data class Ready(val bitmap: androidx.compose.ui.graphics.ImageBitmap) : ImageLoadResult
}

@Composable
private fun CardsEmptyState(
    hasQuery: Boolean,
    onClearQuery: () -> Unit,
    onNewCard: () -> Unit,
    enabled: Boolean,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(if (hasQuery) "没有匹配的 key" else "当前卡组还没有卡片", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        if (hasQuery) OutlinedButton(onClick = onClearQuery, enabled = enabled) { Text("清除搜索") }
        else Button(onClick = onNewCard, enabled = enabled) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("录入第一张")
        }
    }
}

@Composable
fun CardsContextPane(
    card: MemoryCard?,
    imageContent: CardImageContent,
    keySaveRevision: Long,
    isBusy: Boolean,
    compactHeight: Boolean,
    onSaveKey: (String) -> Unit,
    onRedraw: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (card == null) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("选择卡片后显示详情", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    var editing by remember(card.id) { mutableStateOf(false) }
    var draft by remember(card.id) { mutableStateOf(card.keyText) }
    var confirmDelete by remember(card.id) { mutableStateOf(false) }
    var confirmArchive by remember(card.id) { mutableStateOf(false) }
    var pendingSaveRevision by remember(card.id) { mutableStateOf<Long?>(null) }
    androidx.compose.runtime.LaunchedEffect(keySaveRevision) {
        if (pendingSaveRevision != null && keySaveRevision > requireNotNull(pendingSaveRevision)) {
            pendingSaveRevision = null
            editing = false
        }
    }
    BoxWithConstraints(modifier) {
        val compact = compactHeight || maxHeight <= 400.dp
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = if (compact) 10.dp else 16.dp, vertical = if (compact) 6.dp else 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("卡片详情", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { editing = true }, enabled = !isBusy) { Icon(Icons.Default.Edit, "编辑 key") }
                    IconButton(onClick = { confirmDelete = true }, enabled = !isBusy) {
                        Icon(Icons.Default.Delete, "删除卡片", tint = DangerCoral)
                    }
                }
                if (editing) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth().testTag("key 编辑输入"),
                        label = { Text("文字 key") },
                        enabled = !isBusy,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { draft = card.keyText; editing = false }, enabled = !isBusy) { Text("取消") }
                        Button(
                            onClick = { pendingSaveRevision = keySaveRevision; onSaveKey(draft) },
                            enabled = draft.isNotBlank() && !isBusy && pendingSaveRevision == null,
                        ) { Text("保存") }
                    }
                } else if (compact) {
                    CompactCardDetails(card, imageContent, isBusy, onRedraw, { confirmArchive = true })
                } else {
                    Text(card.keyText, style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    imageContent(card, Modifier.width(150.dp).aspectRatio(DrawingCanvasSpec.aspectRatio).border(1.dp, StrongDivider, MaterialTheme.shapes.small))
                    Spacer(Modifier.height(12.dp))
                    ReviewText(card)
                    Spacer(Modifier.height(16.dp))
                    DetailActions(isBusy, onRedraw) { confirmArchive = true }
                }
            }
        }
    }
    if (confirmArchive) {
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text("归档卡片？") },
            text = { Text("归档后不会出现在当前卡片列表和随机推荐中。") },
            dismissButton = { TextButton(onClick = { confirmArchive = false }) { Text("取消") } },
            confirmButton = { TextButton(onClick = { confirmArchive = false; onArchive() }) { Text("确认归档") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除卡片？") },
            text = { Text("删除后将同时移除图片，且无法撤销。") },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }, enabled = !isBusy) {
                    Text("确认删除", color = DangerCoral)
                }
            },
        )
    }
}

@Composable
private fun CompactCardDetails(
    card: MemoryCard,
    imageContent: CardImageContent,
    isBusy: Boolean,
    onRedraw: () -> Unit,
    onArchive: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        imageContent(
            card,
            Modifier.width(54.dp).aspectRatio(DrawingCanvasSpec.aspectRatio).border(1.dp, StrongDivider, MaterialTheme.shapes.small)
                .testTag("紧凑详情预览"),
        )
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(card.keyText, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ReviewText(card)
            Spacer(Modifier.height(6.dp))
            DetailActions(isBusy, onRedraw, onArchive)
        }
    }
}

@Composable
private fun ReviewText(card: MemoryCard) {
    Text(reviewSummary(card), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("已复习 ${card.review.seenCount} 次", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DetailActions(isBusy: Boolean, onRedraw: () -> Unit, onArchive: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilledTonalButton(onClick = onRedraw, enabled = !isBusy, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(4.dp)); Text("重新绘制", maxLines = 1)
        }
        OutlinedButton(onClick = onArchive, enabled = !isBusy, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Archive, null); Spacer(Modifier.width(4.dp)); Text("归档", maxLines = 1)
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, label = { Text("卡组名称") }, singleLine = true) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = { Button(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text("确定") } },
    )
}

private sealed interface DeckDialog {
    data object Create : DeckDialog
    data class Rename(val name: String) : DeckDialog
}

private fun reviewSummary(card: MemoryCard): String {
    val reviewed = card.review.lastReviewedAt?.let {
        DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it))
    } ?: "尚未复习"
    return "$reviewed · 权重 ${"%.2f".format(card.review.weight)}"
}
