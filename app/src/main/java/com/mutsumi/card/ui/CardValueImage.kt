package com.mutsumi.card.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.mutsumi.card.domain.workflow.MemoryCard
import java.io.File

@Composable
fun CardValueImage(
    card: MemoryCard,
    imageRoot: File,
    modifier: Modifier = Modifier,
) {
    if (card.valueImagePath.startsWith("sample://")) {
        SampleCardImage(card = card, modifier = modifier)
        return
    }

    val imageFile = remember(card.valueImagePath, imageRoot) {
        val file = File(imageRoot, card.valueImagePath)
        check(file.exists()) { "卡片图片不存在：${card.valueImagePath}" }
        file
    }
    val bitmap = remember(imageFile) {
        BitmapFactory.decodeFile(imageFile.absolutePath)?.asImageBitmap()
            ?: error("卡片图片无法解码：${card.valueImagePath}")
    }

    Image(
        bitmap = bitmap,
        contentDescription = "图片 value：${card.keyText}",
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun SampleCardImage(card: MemoryCard, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(Color(0xFFF8FAF9), size = size)
        when (card.valueImagePath) {
            "sample://rain" -> {
                repeat(9) { index ->
                    val x = size.width * (0.1f + index * 0.1f)
                    drawLine(primary, Offset(x, size.height * 0.2f), Offset(x - 28f, size.height * 0.76f), strokeWidth = 8f)
                }
            }
            "sample://sun" -> {
                drawCircle(secondary, radius = size.minDimension * 0.22f, center = Offset(size.width * 0.68f, size.height * 0.32f))
                drawOval(primary.copy(alpha = 0.45f), topLeft = Offset(size.width * 0.12f, size.height * 0.52f), size = Size(size.width * 0.74f, size.height * 0.22f))
            }
            else -> {
                drawRect(primary.copy(alpha = 0.18f), topLeft = Offset(0f, size.height * 0.52f), size = Size(size.width, size.height * 0.48f))
                drawCircle(secondary, radius = size.minDimension * 0.18f, center = Offset(size.width * 0.72f, size.height * 0.34f))
            }
        }
    }
}
