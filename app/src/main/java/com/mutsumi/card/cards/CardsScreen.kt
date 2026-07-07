package com.mutsumi.card.cards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mutsumi.card.domain.workflow.MemoryCard
import com.mutsumi.card.ui.CardValueImage
import java.io.File

@Composable
fun CardsScreen(
    cards: List<MemoryCard>,
    selectedCardId: Long?,
    imageRoot: File,
    onSelectCard: (MemoryCard) -> Unit,
    onAddSampleCard: () -> Unit,
) {
    val selected = cards.firstOrNull { it.id == selectedCardId }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(text = "默认卡组", style = MaterialTheme.typography.titleLarge)
                Text(text = "共 ${cards.size} 张卡片", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onAddSampleCard) {
                Text("新增示例")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        selected?.let {
            OutlinedCard(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("当前选择", style = MaterialTheme.typography.labelMedium)
                    Text(it.keyText, fontWeight = FontWeight.Bold)
                    Text(it.valueDescription, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CardValueImage(
                        card = it,
                        imageRoot = imageRoot,
                        modifier = Modifier.fillMaxWidth().aspectRatio(2f),
                    )
                }
            }
        }
        LazyColumn {
            items(cards, key = { it.id }) { card ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable { onSelectCard(card) },
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(card.keyText, fontWeight = FontWeight.Bold)
                        Text(
                            "权重 ${card.weight} · 笔画 ${card.strokeCount}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
