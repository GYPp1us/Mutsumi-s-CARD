package com.mutsumi.card.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ReviewDirection {
    Forward,
    Reverse,
}

private val Context.mutsumiCardDataStore by preferencesDataStore(name = "mutsumi-card")

interface AppPreferences {
    val currentDeckId: Flow<Long?>
    val reviewDirection: Flow<ReviewDirection>
    val brushColorArgb: Flow<Int>
    val brushSize: Flow<Float>
    val eraserSize: Flow<Float>

    suspend fun setCurrentDeckId(deckId: Long)
    suspend fun setReviewDirection(direction: ReviewDirection)
    suspend fun setBrushColorArgb(colorArgb: Int)
    suspend fun setBrushSize(size: Float)
    suspend fun setEraserSize(size: Float)
}

class DataStoreAppPreferences(
    private val dataStore: DataStore<Preferences>,
) : AppPreferences {
    override val currentDeckId: Flow<Long?> = dataStore.data.map { it[CURRENT_DECK_ID] }
    override val reviewDirection: Flow<ReviewDirection> = dataStore.data.map { preferences ->
        preferences[REVIEW_DIRECTION]?.let(ReviewDirection::valueOf) ?: ReviewDirection.Forward
    }
    override val brushColorArgb: Flow<Int> = dataStore.data.map {
        it[BRUSH_COLOR_ARGB] ?: DEFAULT_BRUSH_COLOR_ARGB
    }
    override val brushSize: Flow<Float> = dataStore.data.map {
        it[BRUSH_SIZE] ?: DEFAULT_BRUSH_SIZE
    }
    override val eraserSize: Flow<Float> = dataStore.data.map {
        it[ERASER_SIZE] ?: DEFAULT_ERASER_SIZE
    }

    override suspend fun setCurrentDeckId(deckId: Long) {
        require(deckId > 0) { "卡组 ID 必须大于 0" }
        dataStore.edit { it[CURRENT_DECK_ID] = deckId }
    }

    override suspend fun setReviewDirection(direction: ReviewDirection) {
        dataStore.edit { it[REVIEW_DIRECTION] = direction.name }
    }

    override suspend fun setBrushColorArgb(colorArgb: Int) {
        dataStore.edit { it[BRUSH_COLOR_ARGB] = colorArgb }
    }

    override suspend fun setBrushSize(size: Float) {
        require(size.isFinite() && size > 0f) { "笔刷大小必须大于 0" }
        dataStore.edit { it[BRUSH_SIZE] = size }
    }

    override suspend fun setEraserSize(size: Float) {
        require(size.isFinite() && size > 0f) { "橡皮大小必须大于 0" }
        dataStore.edit { it[ERASER_SIZE] = size }
    }

    companion object {
        private val CURRENT_DECK_ID = longPreferencesKey("current_deck_id")
        private val REVIEW_DIRECTION = stringPreferencesKey("review_direction")
        private val BRUSH_COLOR_ARGB = intPreferencesKey("brush_color_argb")
        private val BRUSH_SIZE = floatPreferencesKey("brush_size")
        private val ERASER_SIZE = floatPreferencesKey("eraser_size")

        private const val DEFAULT_BRUSH_COLOR_ARGB: Int = 0xFF202623.toInt()
        private const val DEFAULT_BRUSH_SIZE = 12f
        private const val DEFAULT_ERASER_SIZE = 24f

        fun create(context: Context): DataStoreAppPreferences = DataStoreAppPreferences(
            context.applicationContext.mutsumiCardDataStore,
        )
    }
}
