package com.mutsumi.card.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class CardRow(val id: Long, val key: String, val meta: String)

@Composable
fun CardsScreen() {
    val cards = listOf(
        CardRow(1, "雨の音", "权重 1.0"),
        CardRow(2, "木漏れ日", "权重 1.3"),
        CardRow(3, "夕焼け", "权重 0.7"),
    )
    Column(modifier = Modifier.fillMaxSize()) {
        Text(text = "默认卡组", style = MaterialTheme.typography.titleLarge)
        Text(text = "卡片以条目管理，点击后进入预览和编辑。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(12.dp))
        LazyColumn {
            items(cards, key = { it.id }) { card ->
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(card.key, fontWeight = FontWeight.Bold)
                        Text(card.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

