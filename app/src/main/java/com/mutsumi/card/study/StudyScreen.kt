package com.mutsumi.card.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mutsumi.card.domain.review.ReviewFeedback
import com.mutsumi.card.domain.workflow.MemoryCard

@Composable
fun StudyScreen(
    cards: List<MemoryCard>,
    currentCardId: Long?,
    onFeedback: (Long, ReviewFeedback) -> String,
) {
    if (cards.isEmpty()) {
        EmptyStudyState()
        return
    }

    val card = cards.firstOrNull { it.id == currentCardId } ?: cards.first()
    var imageVisible by remember(card.id) { mutableStateOf(false) }
    var message by remember { mutableStateOf("点击卡片查看图片") }

    LaunchedEffect(card.id) {
        imageVisible = false
        message = "当前推荐：${card.keyText}"
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(text = "随机推荐", style = MaterialTheme.typography.titleMedium)
        Text(text = message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(16.dp))
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .aspectRatio(4f / 3f)
                    .clickable {
                        imageVisible = true
                        message = "选择复习反馈"
                    },
                shape = RoundedCornerShape(8.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(18.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(card.keyText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (imageVisible) card.valueDescription else "点击显示图片")
                    }
                    FeedbackRow { feedback ->
                        message = onFeedback(card.id, feedback)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyStudyState() {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "随机推荐", style = MaterialTheme.typography.titleMedium)
        Text("当前卡组为空，请先到“绘制”录入第一张卡片。")
    }
}

@Composable
private fun FeedbackRow(onFeedback: (ReviewFeedback) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(modifier = Modifier.weight(1f), onClick = { onFeedback(ReviewFeedback.Again) }) {
            Text("记不住")
        }
        OutlinedButton(modifier = Modifier.weight(1f), onClick = { onFeedback(ReviewFeedback.Unsure) }) {
            Text("模糊")
        }
        Button(modifier = Modifier.weight(1f), onClick = { onFeedback(ReviewFeedback.Know) }) {
            Text("记住了")
        }
    }
}

