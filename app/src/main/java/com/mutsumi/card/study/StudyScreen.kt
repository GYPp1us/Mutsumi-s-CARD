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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mutsumi.card.domain.review.ReviewFeedback

@Composable
fun StudyScreen() {
    var imageVisible by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("点击卡片查看图片") }

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
                    Text("雨の音", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (imageVisible) "图片 value" else "点击显示图片")
                    }
                    FeedbackRow {
                        imageVisible = false
                        message = when (it) {
                            ReviewFeedback.Again -> "已标记为记不住，后续会更常出现"
                            ReviewFeedback.Unsure -> "已标记为模糊，后续会稍微增加"
                            ReviewFeedback.Know -> "已标记为记住了，后续会降低频率"
                        }
                    }
                }
            }
        }
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

