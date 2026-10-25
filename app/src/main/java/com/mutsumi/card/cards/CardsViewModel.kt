package com.mutsumi.card.cards

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mutsumi.card.data.CardRepository
import com.mutsumi.card.data.LibrarySelection
import com.mutsumi.card.data.LibraryAction
import com.mutsumi.card.data.LibraryImpact
import com.mutsumi.card.data.preferences.AppPreferences
import com.mutsumi.card.domain.model.Deck
import com.mutsumi.card.domain.model.MemoryCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

data class CardsUiState(
    val decks: List<Deck> = emptyList(),
    val currentDeck: Deck? = null,
    val query: String = "",
    val cards: List<MemoryCard> = emptyList(),
    val selectedCard: MemoryCard? = null,
    val isLoading: Boolean = true,
    val isBusy: Boolean = false,
    val keySaveRevision: Long = 0L,
    val atRoot: Boolean = false,
    val showArchived: Boolean = false,
    val selection: LibrarySelection = LibrarySelection(),
    val pendingDeleteImpact: LibraryImpact? = null,
)

class CardsRecoverableException(message: String, cause: Throwable? = null) : Exception(message, cause)

sealed interface CardsEvent {
    data class Message(val text: String) : CardsEvent
    data class OpenNewCard(val deckId: Long) : CardsEvent
    data class OpenRedraw(val card: MemoryCard) : CardsEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class CardsViewModel(
    private val repository: CardRepository,
    private val preferences: AppPreferences,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val query = savedStateHandle.getStateFlow(QUERY_KEY, "")
    private val selectedCardId = savedStateHandle.getStateFlow<Long?>(SELECTED_CARD_KEY, null)
    private val root = savedStateHandle.getStateFlow("cards.atRoot", false)
    private val archived = savedStateHandle.getStateFlow("cards.showArchived", false)
    private val mutableUiState = MutableStateFlow(CardsUiState())
    private val eventChannel = Channel<CardsEvent>(Channel.BUFFERED)
    private val mutationMutex = Mutex()

    val uiState: StateFlow<CardsUiState> = mutableUiState.asStateFlow()
    val events = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            val deckSelection = combine(repository.libraryDecks, preferences.currentDeckId) { decks, selectedId ->
                val selected = decks.firstOrNull { it.id == selectedId } ?: decks.firstOrNull()
                DeckSelection(decks, selected, selectedId)
            }
            combine(deckSelection, query, root, archived) { selection, currentQuery, atRoot, showArchived ->
                BrowserRequest(selection, currentQuery, atRoot, showArchived)
            }.flatMapLatest { (selection, currentQuery, atRoot, showArchived) ->
                val (decks, deck, preferredId) = selection
                if (deck != null && preferredId != deck.id) repairPreferredDeck(deck.id)
                if (deck == null || atRoot) {
                    MutableStateFlow(Snapshot(decks, null, currentQuery, emptyList(), atRoot = atRoot, showArchived = showArchived))
                } else {
                    repository.managementCards(deck.id, currentQuery, showArchived).combine(selectedCardId) { cards, selectedId ->
                        Snapshot(decks, deck, currentQuery, cards, selectedId, atRoot, showArchived)
                    }
                }
            }.collect { snapshot ->
                mutableUiState.value = mutableUiState.value.copy(
                    decks = snapshot.decks,
                    currentDeck = snapshot.deck,
                    query = snapshot.query,
                    cards = snapshot.cards,
                    selectedCard = snapshot.cards.firstOrNull { it.id == snapshot.selectedId },
                    isLoading = false,
                    atRoot = snapshot.atRoot,
                    showArchived = snapshot.showArchived,
                    selection = mutableUiState.value.selection.let { selected -> LibrarySelection(
                        selected.cardIds.intersect(snapshot.cards.mapTo(hashSetOf()) { it.id }),
                        selected.deckIds.intersect(snapshot.decks.filter { it.parentId == snapshot.deck?.id &&
                            (snapshot.showArchived || !it.archived) && it.name.contains(snapshot.query, ignoreCase = true) }.mapTo(hashSetOf()) { it.id }),
                    ) },
                )
            }
        }
    }

    fun setQuery(value: String) {
        savedStateHandle[QUERY_KEY] = value
    }

    fun clearQuery() = setQuery("")

    fun selectCard(cardId: Long?) {
        savedStateHandle[SELECTED_CARD_KEY] = cardId
        mutableUiState.value = mutableUiState.value.copy(
            selectedCard = mutableUiState.value.cards.firstOrNull { it.id == cardId },
        )
    }

    fun switchDeck(deckId: Long) {
        val deck = uiState.value.decks.firstOrNull { it.id == deckId }
        if (deck == null) {
            emitMessage("卡组不存在")
            return
        }
        launchMutation {
            savedStateHandle["cards.atRoot"] = false
            clearSelection()
            preferences.setCurrentDeckId(deckId)
            selectCard(null)
            eventChannel.send(CardsEvent.Message("已切换到“${deck.name}”"))
        }
    }

    fun createDeck(name: String) {
        val normalized = normalizedOrReport(name, "请输入卡组名称") ?: return
        launchMutation {
            val deckId = recoverStorage { repository.createChildDeck(normalized, uiState.value.currentDeck?.id) }
            preferences.setCurrentDeckId(deckId)
            savedStateHandle["cards.atRoot"] = false
            selectCard(null)
            eventChannel.send(CardsEvent.Message("已创建卡组“$normalized”"))
        }
    }

    fun browseRoot() {
        if (uiState.value.isBusy) return
        clearSelection(); selectCard(null)
        savedStateHandle["cards.atRoot"] = true
    }

    fun toggleArchived() { savedStateHandle["cards.showArchived"] = !uiState.value.showArchived; clearSelection() }

    fun toggleCardSelection(id: Long) {
        if (uiState.value.isBusy) return
        val selected = uiState.value.selection
        mutableUiState.value = uiState.value.copy(selection = selected.copy(cardIds = toggle(selected.cardIds, id)), pendingDeleteImpact = null)
    }

    fun toggleDeckSelection(id: Long) {
        if (uiState.value.isBusy) return
        val selected = uiState.value.selection
        mutableUiState.value = uiState.value.copy(selection = selected.copy(deckIds = toggle(selected.deckIds, id)), pendingDeleteImpact = null)
    }

    fun selectAll() {
        if (uiState.value.isBusy) return
        val state = uiState.value
        mutableUiState.value = state.copy(pendingDeleteImpact = null, selection = LibrarySelection(state.cards.mapTo(hashSetOf()) { it.id },
            state.decks.filter { it.parentId == state.currentDeck?.id && (state.showArchived || !it.archived) && it.name.contains(state.query, ignoreCase = true) }.mapTo(hashSetOf()) { it.id }))
    }

    fun clearSelection() { mutableUiState.value = uiState.value.copy(selection = LibrarySelection(), pendingDeleteImpact = null) }

    fun previewSelectedDeletion() {
        val selected = uiState.value.selection
        if (selected.cardIds.isEmpty() && selected.deckIds.isEmpty()) return emitMessage("请先选择项目")
        launchMutation {
            val impact = repository.managementImpact(selected)
            mutableUiState.value = uiState.value.copy(pendingDeleteImpact = impact)
        }
    }

    fun dismissSelectedDeletion() { if (!uiState.value.isBusy) mutableUiState.value = uiState.value.copy(pendingDeleteImpact = null) }

    fun manageSelected(action: LibraryAction, target: Long? = null) {
        val selected = uiState.value.selection
        val expectedRevision = uiState.value.pendingDeleteImpact?.revision
        if (action == LibraryAction.Delete && uiState.value.pendingDeleteImpact == null) return previewSelectedDeletion()
        if (selected.cardIds.isEmpty() && selected.deckIds.isEmpty()) return emitMessage("请先选择项目")
        launchMutation {
            val count = try { recoverStorage { repository.manage(selected, action, target, expectedRevision) } }
            catch (error: IllegalArgumentException) { throw CardsRecoverableException(error.message ?: "无法执行批量操作", error) }
            clearSelection(); selectCard(null)
            eventChannel.send(CardsEvent.Message("已${action.label} $count 个项目"))
        }
    }

    private fun toggle(values: Set<Long>, id: Long) = if (id in values) values - id else values + id

    fun renameCurrentDeck(name: String) {
        val normalized = normalizedOrReport(name, "请输入卡组名称") ?: return
        val deck = uiState.value.currentDeck ?: return emitMessage("当前没有可用卡组")
        launchMutation {
            recoverStorage { repository.renameDeck(deck.id, normalized) }
            eventChannel.send(CardsEvent.Message("卡组已改名"))
        }
    }

    fun updateSelectedKey(keyText: String) {
        val normalized = normalizedOrReport(keyText, "请输入文字 key") ?: return
        val card = uiState.value.selectedCard ?: return emitMessage("请先选择卡片")
        launchMutation {
            recoverStorage { repository.updateCard(card.id, normalized) }
            mutableUiState.value = mutableUiState.value.copy(
                keySaveRevision = mutableUiState.value.keySaveRevision + 1,
            )
            eventChannel.send(CardsEvent.Message("key 已保存"))
        }
    }

    fun archiveSelectedCard() {
        val card = uiState.value.selectedCard ?: return emitMessage("请先选择卡片")
        launchMutation {
            recoverStorage { repository.archiveCard(card.id) }
            selectCard(null)
            eventChannel.send(CardsEvent.Message("卡片已归档"))
        }
    }

    fun deleteSelectedCard() {
        val card = uiState.value.selectedCard ?: return emitMessage("请先选择卡片")
        launchMutation {
            recoverStorage { repository.deleteCard(card.id) }
            selectCard(null)
            eventChannel.send(CardsEvent.Message("卡片已删除"))
        }
    }

    fun requestNewCard() {
        val deck = uiState.value.currentDeck ?: return emitMessage("当前没有可用卡组")
        eventChannel.trySend(CardsEvent.OpenNewCard(deck.id)).getOrThrow()
    }

    fun requestRedrawSelectedCard() {
        val card = uiState.value.selectedCard ?: return emitMessage("请先选择卡片")
        eventChannel.trySend(CardsEvent.OpenRedraw(card)).getOrThrow()
    }

    private fun launchMutation(block: suspend () -> Unit) {
        if (!mutationMutex.tryLock()) {
            emitMessage("操作进行中，请稍候")
            return
        }
        mutableUiState.value = mutableUiState.value.copy(isBusy = true)
        viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: CardsRecoverableException) {
                Log.w(LOG_TAG, error.message, error)
                eventChannel.send(CardsEvent.Message(error.message ?: "操作失败"))
            } catch (error: Exception) {
                Log.e(LOG_TAG, "卡片管理发生未知异常", error)
                throw error
            } finally {
                mutableUiState.value = mutableUiState.value.copy(isBusy = false)
                mutationMutex.unlock()
            }
        }
    }

    private suspend fun repairPreferredDeck(deckId: Long) {
        mutationMutex.withLock {
            mutableUiState.value = mutableUiState.value.copy(isBusy = true)
            try {
                preferences.setCurrentDeckId(deckId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(LOG_TAG, "修复当前卡组偏好失败", error)
                throw error
            } finally {
                mutableUiState.value = mutableUiState.value.copy(isBusy = false)
            }
        }
    }

    private suspend fun <T> recoverStorage(block: suspend () -> T): T = try {
        block()
    } catch (error: IOException) {
        throw CardsRecoverableException(error.message ?: "存储操作失败", error)
    }

    private fun normalizedOrReport(value: String, message: String): String? {
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            emitMessage(message)
            return null
        }
        return normalized
    }

    private fun emitMessage(message: String) {
        eventChannel.trySend(CardsEvent.Message(message)).getOrThrow()
    }

    private data class Snapshot(
        val decks: List<Deck>,
        val deck: Deck?,
        val query: String,
        val cards: List<MemoryCard>,
        val selectedId: Long? = null,
        val atRoot: Boolean = false,
        val showArchived: Boolean = false,
    )

    private data class DeckSelection(
        val decks: List<Deck>,
        val deck: Deck?,
        val preferredId: Long?,
    )

    private data class BrowserRequest(val selection: DeckSelection, val query: String, val atRoot: Boolean, val showArchived: Boolean)

    private companion object {
        const val QUERY_KEY = "cards.query"
        const val SELECTED_CARD_KEY = "cards.selectedCardId"
        const val LOG_TAG = "MutsumiCard"
    }
}
