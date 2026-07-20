package com.mutsumi.card.cards

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.mutsumi.card.data.CardRepository
import com.mutsumi.card.data.preferences.AppPreferences
import com.mutsumi.card.data.preferences.ReviewDirection
import com.mutsumi.card.domain.model.Deck
import com.mutsumi.card.domain.model.MemoryCard
import com.mutsumi.card.domain.model.ReviewState
import com.mutsumi.card.domain.review.ReviewFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CardsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun observesSelectedDeckAndPassesSearchLiterally() = runTest(dispatcher) {
        val repository = FakeCardRepository()
        val preferences = FakeAppPreferences(currentDeck = 1L)
        repository.deckFlow.value = listOf(Deck(1, "默认卡组", 2), Deck(2, "生物", 0))
        repository.cardFlows[1L] = MutableStateFlow(listOf(card(11, 1, "100%_细胞")))
        val viewModel = CardsViewModel(repository, preferences, SavedStateHandle())
        advanceUntilIdle()

        viewModel.setQuery("%_")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.currentDeck?.name).isEqualTo("默认卡组")
        assertThat(viewModel.uiState.value.cards.single().keyText).isEqualTo("100%_细胞")
        assertThat(repository.lastCardsRequest).isEqualTo(1L to "%_")
    }

    @Test
    fun missingPreferenceSelectsFirstDeckAndWritesItBack() = runTest(dispatcher) {
        val repository = FakeCardRepository()
        repository.deckFlow.value = listOf(Deck(7, "最早卡组", 0), Deck(9, "后来卡组", 0))
        val preferences = FakeAppPreferences(currentDeck = null)

        val viewModel = CardsViewModel(repository, preferences, SavedStateHandle())
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.currentDeck?.id).isEqualTo(7L)
        assertThat(preferences.currentDeckId.value).isEqualTo(7L)
    }

    @Test
    fun stalePreferenceSelectsFirstDeckAndRepairsPreference() = runTest(dispatcher) {
        val repository = FakeCardRepository()
        repository.deckFlow.value = listOf(Deck(3, "默认卡组", 0), Deck(4, "其他", 0))
        val preferences = FakeAppPreferences(currentDeck = 999L)

        val viewModel = CardsViewModel(repository, preferences, SavedStateHandle())
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.currentDeck?.id).isEqualTo(3L)
        assertThat(preferences.currentDeckId.value).isEqualTo(3L)
    }

    @Test
    fun switchesDeckPersistsSelectionAndClearsSelectedCard() = runTest(dispatcher) {
        val repository = FakeCardRepository()
        repository.deckFlow.value = listOf(Deck(1, "一", 1), Deck(2, "二", 1))
        repository.cardFlows[1L] = MutableStateFlow(listOf(card(11, 1, "甲")))
        repository.cardFlows[2L] = MutableStateFlow(listOf(card(22, 2, "乙")))
        val preferences = FakeAppPreferences(currentDeck = 1L)
        val viewModel = CardsViewModel(repository, preferences, SavedStateHandle())
        advanceUntilIdle()
        viewModel.selectCard(11)

        viewModel.switchDeck(2)
        advanceUntilIdle()

        assertThat(preferences.currentDeckId.value).isEqualTo(2L)
        assertThat(viewModel.uiState.value.currentDeck?.id).isEqualTo(2L)
        assertThat(viewModel.uiState.value.selectedCard).isNull()
        assertThat(viewModel.events.first()).isEqualTo(CardsEvent.Message("已切换到“二”"))
    }

    @Test
    fun createsAndRenamesDeckWithExplicitSuccessFeedback() = runTest(dispatcher) {
        val repository = FakeCardRepository()
        repository.deckFlow.value = listOf(Deck(1, "默认卡组", 0))
        val preferences = FakeAppPreferences(currentDeck = 1L)
        val viewModel = CardsViewModel(repository, preferences, SavedStateHandle())
        advanceUntilIdle()

        viewModel.createDeck("  生物  ")
        advanceUntilIdle()
        val created = viewModel.events.first()
        viewModel.renameCurrentDeck("  植物  ")
        advanceUntilIdle()
        val renamed = viewModel.events.first()

        assertThat(repository.createdName).isEqualTo("生物")
        assertThat(preferences.currentDeckId.value).isEqualTo(2L)
        assertThat(created).isEqualTo(CardsEvent.Message("已创建卡组“生物”"))
        assertThat(repository.renamed).isEqualTo(2L to "植物")
        assertThat(renamed).isEqualTo(CardsEvent.Message("卡组已改名"))
    }

    @Test
    fun editsKeyArchivesAndDeletesSelectedCard() = runTest(dispatcher) {
        val repository = FakeCardRepository()
        repository.deckFlow.value = listOf(Deck(1, "默认卡组", 1))
        repository.cardFlows[1L] = MutableStateFlow(listOf(card(11, 1, "旧 key")))
        val viewModel = CardsViewModel(repository, FakeAppPreferences(1L), SavedStateHandle())
        advanceUntilIdle()
        viewModel.selectCard(11)

        viewModel.updateSelectedKey("  新 key  ")
        advanceUntilIdle()
        assertThat(repository.updated).isEqualTo(11L to "新 key")
        assertThat(viewModel.events.first()).isEqualTo(CardsEvent.Message("key 已保存"))

        viewModel.archiveSelectedCard()
        advanceUntilIdle()
        assertThat(repository.archivedId).isEqualTo(11L)
        assertThat(viewModel.events.first()).isEqualTo(CardsEvent.Message("卡片已归档"))

        viewModel.selectCard(11)
        viewModel.deleteSelectedCard()
        advanceUntilIdle()
        assertThat(repository.deletedId).isEqualTo(11L)
        assertThat(viewModel.events.first()).isEqualTo(CardsEvent.Message("卡片已删除"))
    }

    @Test
    fun requestsNewCardAndRedrawWithCompleteContext() = runTest(dispatcher) {
        val repository = FakeCardRepository()
        repository.deckFlow.value = listOf(Deck(1, "默认卡组", 1))
        repository.cardFlows[1L] = MutableStateFlow(listOf(card(11, 1, "细胞")))
        val viewModel = CardsViewModel(repository, FakeAppPreferences(1L), SavedStateHandle())
        advanceUntilIdle()

        viewModel.requestNewCard()
        assertThat(viewModel.events.first()).isEqualTo(CardsEvent.OpenNewCard(1L))
        viewModel.selectCard(11)
        viewModel.requestRedrawSelectedCard()
        assertThat(viewModel.events.first()).isEqualTo(CardsEvent.OpenRedraw(card(11, 1, "细胞")))
    }

    @Test
    fun blankInputProducesValidationFeedbackWithoutRepositoryCall() = runTest(dispatcher) {
        val repository = FakeCardRepository()
        repository.deckFlow.value = listOf(Deck(1, "默认卡组", 0))
        val viewModel = CardsViewModel(repository, FakeAppPreferences(1L), SavedStateHandle())
        advanceUntilIdle()

        viewModel.createDeck(" ")
        assertThat(viewModel.events.first()).isEqualTo(CardsEvent.Message("请输入卡组名称"))
        assertThat(repository.createdName).isNull()
    }

    @Test(expected = IllegalStateException::class)
    fun unknownDevelopmentFailureIsNotSwallowed() = runTest(dispatcher) {
        val repository = FakeCardRepository().apply { createFailure = IllegalStateException("程序错误") }
        repository.deckFlow.value = listOf(Deck(1, "默认卡组", 0))
        val viewModel = CardsViewModel(repository, FakeAppPreferences(1L), SavedStateHandle())
        advanceUntilIdle()

        viewModel.createDeck("生物")
        advanceUntilIdle()
    }

    @Test
    fun recoverableStorageFailureProducesFeedback() = runTest(dispatcher) {
        val repository = FakeCardRepository().apply { createFailure = IOException("存储空间不可用") }
        repository.deckFlow.value = listOf(Deck(1, "默认卡组", 0))
        val viewModel = CardsViewModel(repository, FakeAppPreferences(1L), SavedStateHandle())
        advanceUntilIdle()

        viewModel.createDeck("生物")
        advanceUntilIdle()

        assertThat(viewModel.events.first()).isEqualTo(CardsEvent.Message("存储空间不可用"))
    }

    @Test
    fun concurrentMutationIsRejectedWhileBusyAndDoesNotReachRepository() = runTest(dispatcher) {
        val repository = FakeCardRepository()
        repository.deckFlow.value = listOf(Deck(1, "默认卡组", 0))
        repository.createGate = CompletableDeferred()
        val viewModel = CardsViewModel(repository, FakeAppPreferences(1L), SavedStateHandle())
        advanceUntilIdle()

        viewModel.createDeck("第一组")
        dispatcher.scheduler.runCurrent()
        assertThat(viewModel.uiState.value.isBusy).isTrue()
        viewModel.createDeck("第二组")
        assertThat(viewModel.events.first()).isEqualTo(CardsEvent.Message("操作进行中，请稍候"))
        assertThat(repository.createCalls).isEqualTo(1)

        repository.createGate?.complete(Unit)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.isBusy).isFalse()
        assertThat(repository.createdName).isEqualTo("第一组")
    }

    @Test
    fun keySaveRevisionChangesOnlyAfterRepositorySuccess() = runTest(dispatcher) {
        val repository = FakeCardRepository()
        repository.deckFlow.value = listOf(Deck(1, "默认卡组", 1))
        repository.cardFlows[1L] = MutableStateFlow(listOf(card(11, 1, "旧 key")))
        repository.updateGate = CompletableDeferred()
        val viewModel = CardsViewModel(repository, FakeAppPreferences(1L), SavedStateHandle())
        advanceUntilIdle()
        viewModel.selectCard(11)

        viewModel.updateSelectedKey("新 key")
        dispatcher.scheduler.runCurrent()
        assertThat(viewModel.uiState.value.keySaveRevision).isEqualTo(0L)
        repository.updateGate?.complete(Unit)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.keySaveRevision).isEqualTo(1L)
    }

    private fun card(id: Long, deckId: Long, key: String) = MemoryCard(
        id = id,
        deckId = deckId,
        keyText = key,
        valueImagePath = "images/$id.png",
        archived = false,
        review = ReviewState(1.2, 3, 1, 1, 1, 123L),
    )
}

private class FakeCardRepository : CardRepository {
    val deckFlow = MutableStateFlow<List<Deck>>(emptyList())
    val cardFlows = mutableMapOf<Long, MutableStateFlow<List<MemoryCard>>>()
    var lastCardsRequest: Pair<Long, String>? = null
    var createdName: String? = null
    var renamed: Pair<Long, String>? = null
    var updated: Pair<Long, String>? = null
    var archivedId: Long? = null
    var deletedId: Long? = null
    var createFailure: Exception? = null
    var createGate: CompletableDeferred<Unit>? = null
    var updateGate: CompletableDeferred<Unit>? = null
    var createCalls = 0
    override val decks: Flow<List<Deck>> = deckFlow

    override fun cards(deckId: Long, query: String): Flow<List<MemoryCard>> {
        lastCardsRequest = deckId to query
        return cardFlows.getOrPut(deckId) { MutableStateFlow(emptyList()) }
    }

    override suspend fun ensureDefaultDeck() = 1L
    override suspend fun createDeck(name: String): Long {
        createCalls += 1
        createGate?.await()
        createFailure?.let { throw it }
        createdName = name
        val id = 2L
        deckFlow.value = deckFlow.value + Deck(id, name, 0)
        return id
    }
    override suspend fun renameDeck(deckId: Long, name: String) {
        renamed = deckId to name
        deckFlow.value = deckFlow.value.map { if (it.id == deckId) it.copy(name = name) else it }
    }
    override suspend fun saveCard(deckId: Long, keyText: String, png: ByteArray) = 1L
    override suspend fun saveCard(
        deckId: Long,
        keyText: String,
        frontPng: ByteArray?,
        backPng: ByteArray,
    ) = 1L
    override suspend fun updateCard(cardId: Long, keyText: String, png: ByteArray?) {
        updateGate?.await()
        updated = cardId to keyText
    }
    override suspend fun archiveCard(cardId: Long) { archivedId = cardId }
    override suspend fun deleteCard(cardId: Long) { deletedId = cardId }
    override suspend fun applyFeedback(cardId: Long, feedback: ReviewFeedback, now: Long) = Unit
    override suspend fun pickRecommendedCard(deckId: Long, recentCardIds: List<Long>) = null
    override suspend fun retryPendingImageCleanup() = Unit
}

private class FakeAppPreferences(currentDeck: Long?) : AppPreferences {
    override val currentDeckId = MutableStateFlow(currentDeck)
    override val reviewDirection = MutableStateFlow(ReviewDirection.Forward)
    override val brushColorArgb = MutableStateFlow(0)
    override val brushSize = MutableStateFlow(12f)
    override val eraserSize = MutableStateFlow(24f)
    override suspend fun setCurrentDeckId(deckId: Long) { currentDeckId.value = deckId }
    override suspend fun setReviewDirection(direction: ReviewDirection) { reviewDirection.value = direction }
    override suspend fun setBrushColorArgb(colorArgb: Int) { brushColorArgb.value = colorArgb }
    override suspend fun setBrushSize(size: Float) { brushSize.value = size }
    override suspend fun setEraserSize(size: Float) { eraserSize.value = size }
}
