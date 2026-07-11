package com.mutsumi.card.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.mutsumi.card.data.AppContainer
import com.mutsumi.card.data.RoomCardRepository
import com.mutsumi.card.data.image.FileCardImageStore
import com.mutsumi.card.data.local.MutsumiCardDatabase
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppPreferencesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun persistsAllMvpPreferences() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { temporaryFolder.newFile("preferences.preferences_pb") },
        )
        val preferences = DataStoreAppPreferences(dataStore)

        preferences.setCurrentDeckId(42L)
        preferences.setReviewDirection(ReviewDirection.Reverse)
        preferences.setBrushColorArgb(0xFF123456.toInt())
        preferences.setBrushSize(18.5f)
        preferences.setEraserSize(36f)

        assertThat(preferences.currentDeckId.first()).isEqualTo(42L)
        assertThat(preferences.reviewDirection.first()).isEqualTo(ReviewDirection.Reverse)
        assertThat(preferences.brushColorArgb.first()).isEqualTo(0xFF123456.toInt())
        assertThat(preferences.brushSize.first()).isEqualTo(18.5f)
        assertThat(preferences.eraserSize.first()).isEqualTo(36f)
    }

    @Test
    fun containerReplacesMissingSelectedDeckWithDefaultDeck() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, MutsumiCardDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { temporaryFolder.newFile("selection.preferences_pb") },
        )
        val preferences = DataStoreAppPreferences(dataStore)
        val imageStore = FileCardImageStore(temporaryFolder.newFolder("files"))
        val repository = RoomCardRepository(
            dao = database.cardDao(),
            imageStore = imageStore,
        )
        val container = AppContainer(repository, preferences, imageStore)
        preferences.setCurrentDeckId(999L)

        val resolved = container.ensureSelectedDeck()

        assertThat(preferences.currentDeckId.first()).isEqualTo(resolved)
        assertThat(repository.decks.first().map { it.id }).containsExactly(resolved)
        assertThat(container.imageStore).isSameInstanceAs(imageStore)
        database.close()
    }
}
