package com.mutsumi.card.data

import android.content.Context
import com.mutsumi.card.data.image.CardImageStore
import com.mutsumi.card.data.image.FileCardImageStore
import com.mutsumi.card.data.local.MutsumiCardDatabase
import com.mutsumi.card.data.preferences.AppPreferences
import com.mutsumi.card.data.preferences.DataStoreAppPreferences
import kotlinx.coroutines.flow.first
import java.io.File

class AppContainer(
    val cardRepository: CardRepository,
    val appPreferences: AppPreferences,
    val imageStore: CardImageStore,
) {
    suspend fun ensureSelectedDeck(): Long {
        cardRepository.retryPendingImageCleanup()
        val defaultDeckId = cardRepository.ensureDefaultDeck()
        val existingIds = cardRepository.decks.first().mapTo(mutableSetOf()) { it.id }
        val preferred = appPreferences.currentDeckId.first()
        val selected = preferred
            ?.takeIf { it in existingIds }
            ?: defaultDeckId
        if (selected != preferred) {
            appPreferences.setCurrentDeckId(selected)
        }
        return selected
    }

    companion object {
        fun create(context: Context): AppContainer {
            val database = MutsumiCardDatabase.build(context)
            val imageStore = FileCardImageStore(File(context.filesDir, "card-store-v2"))
            return AppContainer(
                cardRepository = RoomCardRepository(database.cardDao(), imageStore),
                appPreferences = DataStoreAppPreferences.create(context),
                imageStore = imageStore,
            )
        }
    }
}
