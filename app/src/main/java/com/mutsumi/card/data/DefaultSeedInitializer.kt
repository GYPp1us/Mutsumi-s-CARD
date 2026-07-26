package com.mutsumi.card.data

import android.content.Context
import com.mutsumi.card.draw.DrawingCanvasSpec
import com.mutsumi.card.draw.MarkdownLayerRenderer
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class DefaultSeedInitializer(
    private val context: Context,
    private val repository: CardRepository,
) {
    suspend fun initialize() {
        val marker = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (marker.getBoolean(INITIALIZED, false)) return
        if (repository.decks.first().isNotEmpty()) {
            marker.edit().putBoolean(INITIALIZED, true).apply()
            return
        }
        val deckId = repository.createDeck("入门示例")
        val cards = Json.decodeFromString<List<SeedCard>>(
            context.assets.open("default-cards.json").bufferedReader().use { it.readText() },
        )
        // Room 卡片列表按更新时间倒序，反向写入才能让第一张示例卡成为首次推荐。
        cards.asReversed().forEach { seed ->
            repository.saveCard(
                deckId = deckId,
                keyText = seed.key,
                frontPng = draw(seed.front),
                backPng = draw(seed.back),
            )
        }
        marker.edit().putBoolean(INITIALIZED, true).apply()
    }

    private fun draw(text: String): ByteArray {
        val bitmap = requireNotNull(
            MarkdownLayerRenderer(context).render(text, DrawingCanvasSpec.width, DrawingCanvasSpec.height),
        ) { "默认卡片 Markdown 渲染失败" }
        return java.io.ByteArrayOutputStream().use { output ->
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) { "默认卡片图片生成失败" }
            bitmap.recycle()
            output.toByteArray()
        }
    }

    @Serializable
    private data class SeedCard(val key: String, val front: String, val back: String)

    private companion object {
        const val PREFERENCES = "default-seed"
        const val INITIALIZED = "initialized"
    }
}
