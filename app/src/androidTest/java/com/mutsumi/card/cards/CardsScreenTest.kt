package com.mutsumi.card.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.mutsumi.card.domain.model.Deck
import com.mutsumi.card.domain.model.MemoryCard
import com.mutsumi.card.domain.model.ReviewState
import com.mutsumi.card.ui.adaptive.AppLayoutMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CardsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun cardRowUsesPortraitThumbnailAndOpensBottomSheet() {
        var selected by mutableStateOf<MemoryCard?>(null)
        compose.setContent {
            MaterialTheme {
                CardsScreen(
                    uiState = state(cards = listOf(card()), selected = selected),
                    layoutMode = AppLayoutMode.Portrait,
                    imageContent = testImage,
                    callbacks = callbacks(onSelectCard = { selected = if (it == null) null else card() }),
                )
            }
        }

        compose.onNodeWithTag("卡片缩略图-11")
            .assertWidthIsEqualTo(36.dp)
            .assertHeightIsEqualTo(72.dp)
        compose.onNodeWithText("细胞").performClick()

        compose.onNodeWithTag("卡片详情弹层").assertIsDisplayed()
    }

    @Test
    fun selectedCardIsShownInPortraitBottomSheetButNotLandscapeWorkspace() {
        compose.setContent {
            MaterialTheme {
                CardsScreen(
                    uiState = state(cards = listOf(card()), selected = card()),
                    layoutMode = AppLayoutMode.Portrait,
                    imageContent = testImage,
                    callbacks = callbacks(),
                )
            }
        }
        compose.onNodeWithTag("卡片详情弹层").assertIsDisplayed()

        compose.setContent {
            MaterialTheme {
                CardsScreen(
                    uiState = state(cards = listOf(card()), selected = card()),
                    layoutMode = AppLayoutMode.LandscapeThreePane,
                    imageContent = testImage,
                    callbacks = callbacks(),
                )
            }
        }
        compose.onAllNodesWithTag("卡片详情弹层").assertCountEquals(0)
    }

    @Test
    fun publicContextPaneEditsKeyAndConfirmsDelete() {
        var edited = ""
        var deleted = false
        compose.setContent {
            MaterialTheme {
                CardsContextPane(
                    card = card(),
                    imageContent = testImage,
                    onSaveKey = { edited = it },
                    keySaveRevision = 0,
                    isBusy = false,
                    compactHeight = false,
                    onRedraw = {},
                    onArchive = {},
                    onDelete = { deleted = true },
                )
            }
        }

        compose.onNodeWithContentDescription("编辑 key").performClick()
        compose.onNodeWithTag("key 编辑输入").performTextInput("膜")
        compose.onNodeWithText("保存").performClick()
        assertEquals("细胞膜", edited)

        compose.onNodeWithContentDescription("删除卡片").performClick()
        assertFalse(deleted)
        compose.onNodeWithText("确认删除").performClick()
        assertTrue(deleted)
    }

    @Test
    fun deckControlsSearchAndEmptyActionAreConnected() {
        var switched: Long? = null
        var query = ""
        var newCard = false
        compose.setContent {
            MaterialTheme {
                CardsScreen(
                    uiState = state(
                        decks = listOf(Deck(1, "默认卡组", 0), Deck(2, "生物", 0)),
                        cards = emptyList(),
                        query = "%_",
                    ),
                    layoutMode = AppLayoutMode.Portrait,
                    imageContent = testImage,
                    callbacks = callbacks(
                        onSwitchDeck = { switched = it },
                        onQueryChange = { query = it },
                        onClearQuery = { query = "" },
                        onNewCard = { newCard = true },
                    ),
                )
            }
        }

        compose.onNodeWithContentDescription("切换卡组").performClick()
        compose.onNodeWithText("生物").performClick()
        assertEquals(2L, switched)
        compose.onNodeWithContentDescription("清除搜索").performClick()
        assertEquals("", query)
        compose.onNodeWithText("没有匹配的 key").assertIsDisplayed()

        compose.setContent {
            MaterialTheme {
                CardsScreen(
                    uiState = state(cards = emptyList()),
                    layoutMode = AppLayoutMode.Portrait,
                    imageContent = testImage,
                    callbacks = callbacks(onNewCard = { newCard = true }),
                )
            }
        }
        compose.onNodeWithText("录入第一张").performClick()
        assertTrue(newCard)
    }

    @Test
    fun busyDisablesRepeatedMutationsAndSaveWaitsForRevision() {
        var saveCalls = 0
        var revision by mutableStateOf(0L)
        var busy by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                CardsContextPane(
                    card = card(),
                    imageContent = testImage,
                    keySaveRevision = revision,
                    isBusy = busy,
                    compactHeight = false,
                    onSaveKey = { saveCalls += 1 },
                    onRedraw = {},
                    onArchive = {},
                    onDelete = {},
                )
            }
        }
        compose.onNodeWithContentDescription("编辑 key").performClick()
        compose.onNodeWithTag("key 编辑输入").assertIsDisplayed()
        compose.onNodeWithText("保存").performClick()
        assertEquals(0, saveCalls)

        busy = false
        compose.waitForIdle()
        compose.onNodeWithText("保存").performClick()
        assertEquals(1, saveCalls)
        compose.onNodeWithTag("key 编辑输入").assertIsDisplayed()
        revision += 1
        compose.waitForIdle()
        compose.onAllNodesWithTag("key 编辑输入").assertCountEquals(0)
    }

    @Test
    fun archiveRequiresConfirmationAndCompactContextKeepsActionsVisible() {
        var archived = false
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Box(Modifier.width(228.dp).height(360.dp)) {
                    CardsContextPane(
                        card = card(),
                        imageContent = testImage,
                        keySaveRevision = 0,
                        isBusy = false,
                        compactHeight = true,
                        onSaveKey = {},
                        onRedraw = {},
                        onArchive = { archived = true },
                        onDelete = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("紧凑详情预览").assertWidthIsEqualTo(54.dp)
        compose.onNodeWithText("归档").assertIsDisplayed().performClick()
        assertFalse(archived)
        compose.onNodeWithText("确认归档").assertIsDisplayed().performClick()
        assertTrue(archived)
        compose.onNodeWithContentDescription("删除卡片").assertIsDisplayed()
    }

    private fun state(
        decks: List<Deck> = listOf(Deck(1, "默认卡组", 1)),
        cards: List<MemoryCard>,
        selected: MemoryCard? = null,
        query: String = "",
    ) = CardsUiState(
        decks = decks,
        currentDeck = decks.first(),
        query = query,
        cards = cards,
        selectedCard = selected,
        isLoading = false,
    )

    private fun card() = MemoryCard(
        id = 11,
        deckId = 1,
        keyText = "细胞",
        valueImagePath = "images/11.png",
        archived = false,
        review = ReviewState(1.25, 3, 1, 1, 1, 123L),
    )

    private val testImage: @androidx.compose.runtime.Composable (MemoryCard, Modifier) -> Unit = { _, modifier ->
        androidx.compose.foundation.layout.Box(modifier.background(Color.LightGray))
    }

    private fun callbacks(
        onQueryChange: (String) -> Unit = {},
        onClearQuery: () -> Unit = {},
        onSelectCard: (Long?) -> Unit = {},
        onSwitchDeck: (Long) -> Unit = {},
        onNewCard: () -> Unit = {},
    ) = CardsCallbacks(
        onQueryChange = onQueryChange, onClearQuery = onClearQuery, onSelectCard = onSelectCard,
        onSwitchDeck = onSwitchDeck, onCreateDeck = {}, onRenameDeck = {}, onNewCard = onNewCard,
        onSaveKey = {}, onRedraw = {}, onArchive = {}, onDelete = {},
    )
}
