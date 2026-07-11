package com.mutsumi.card.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
