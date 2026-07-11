package com.mutsumi.card.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mutsumi.card.domain.review.ReviewFeedback
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CardDaoTest {
    private lateinit var database: MutsumiCardDatabase
    private lateinit var dao: CardDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MutsumiCardDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.cardDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun ensureDefaultDeckIsIdempotentAndCreatesNoCards() = runTest {
        dao.ensureDefaultDeck(100L)
        dao.ensureDefaultDeck(200L)

        val decks = dao.observeDecks().first()
        assertThat(decks).containsExactly(DeckEntity(1L, "默认卡组", 100L, 100L))
        assertThat(dao.observeActiveCardsWithReview(1L).first()).isEmpty()
    }

    @Test
    fun ensureDefaultDeckReusesEarliestExistingDeckWithoutCreatingCards() = runTest {
        val existingId = dao.insertDeck(
            DeckEntity(name = "我的卡组", createdAt = 50L, updatedAt = 50L),
        )

        val ensuredId = dao.ensureDefaultDeck(100L)

        assertThat(ensuredId).isEqualTo(existingId)
        assertThat(dao.observeDecks().first()).containsExactly(
            DeckEntity(existingId, "我的卡组", 50L, 50L),
        )
        assertThat(dao.observeActiveCardsWithReview(existingId).first()).isEmpty()
    }

    @Test
    fun readsCardsTogetherWithReviewStateInStableUpdatedOrder() = runTest {
        val deckId = dao.insertDeck(DeckEntity(name = "测试", createdAt = 1L, updatedAt = 1L))
        val olderId = insertCardWithReview(deckId, "旧卡", updatedAt = 10L)
        val newerId = insertCardWithReview(deckId, "新卡", updatedAt = 20L)

        val cards = dao.observeActiveCardsWithReview(deckId).first()
        assertThat(cards.map { it.card.id }).containsExactly(newerId, olderId).inOrder()
        assertThat(cards.first().reviewState).isEqualTo(ReviewStateEntity(cardId = newerId))
        assertThat(dao.findActiveCardsByKey("新").map { it.id }).containsExactly(newerId)
        assertThat(dao.getCard(olderId)?.keyText).isEqualTo("旧卡")
    }

    @Test
    fun insertCardWithReviewRollsBackCardWhenReviewInsertFails() = runTest {
        val deckId = dao.insertDeck(DeckEntity(name = "测试", createdAt = 1L, updatedAt = 1L))
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_review_insert
            BEFORE INSERT ON review_states
            BEGIN
                SELECT RAISE(ABORT, '测试复习状态写入失败');
            END
            """.trimIndent(),
        )

        val error = runCatching {
            dao.insertCardWithReview(card(deckId, "回滚", updatedAt = 2L))
        }.exceptionOrNull()

        assertThat(error).isNotNull()
        val cursor = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM cards")
        cursor.use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getInt(0)).isEqualTo(0)
        }
    }

    @Test
    fun keySearchTreatsPercentAndUnderscoreLiterally() = runTest {
        val deckId = dao.insertDeck(DeckEntity(name = "测试", createdAt = 1L, updatedAt = 1L))
        val percentId = insertCardWithReview(deckId, "完成 100%", updatedAt = 2L)
        insertCardWithReview(deckId, "完成 1000", updatedAt = 3L)
        val underscoreId = insertCardWithReview(deckId, "字段_a", updatedAt = 4L)
        insertCardWithReview(deckId, "字段Xa", updatedAt = 5L)

        assertThat(dao.findActiveCardsByKey("%").map { it.id }).containsExactly(percentId)
        assertThat(dao.findActiveCardsByKey("_").map { it.id }).containsExactly(underscoreId)
    }

    @Test
    fun archivedCardsAreExcludedFromActiveQueries() = runTest {
        val deckId = dao.insertDeck(DeckEntity(name = "测试", createdAt = 1L, updatedAt = 1L))
        val cardId = insertCardWithReview(deckId, "待归档", updatedAt = 2L)

        dao.updateCard(requireNotNull(dao.getCard(cardId)).copy(archived = true, updatedAt = 3L))

        assertThat(dao.observeActiveCardsWithReview(deckId).first()).isEmpty()
        assertThat(dao.findActiveCardsByKey("待归档")).isEmpty()
        assertThat(dao.getCard(cardId)?.archived).isTrue()
    }

    @Test
    fun feedbackUpdatesWeightCountsAndTimestampAtomically() = runTest {
        val deckId = dao.insertDeck(DeckEntity(name = "测试", createdAt = 1L, updatedAt = 1L))
        val cardId = insertCardWithReview(deckId, "反馈", updatedAt = 2L)

        dao.updateFeedback(cardId, ReviewFeedback.Again, 10L)
        assertThat(dao.getReviewState(cardId)?.weight).isEqualTo(1.8)
        dao.updateFeedback(cardId, ReviewFeedback.Unsure, 20L)
        assertThat(dao.getReviewState(cardId)?.weight).isEqualTo(2.1)
        dao.updateFeedback(cardId, ReviewFeedback.Know, 30L)

        assertThat(dao.getReviewState(cardId)).isEqualTo(
            ReviewStateEntity(
                cardId = cardId,
                weight = 1.15,
                seenCount = 3,
                againCount = 1,
                unsureCount = 1,
                knownCount = 1,
                lastReviewedAt = 30L,
            ),
        )
    }

    @Test
    fun deletingDeckCascadesCardsAndReviewStates() = runTest {
        val deckId = dao.insertDeck(DeckEntity(name = "测试", createdAt = 1L, updatedAt = 1L))
        val cardId = insertCardWithReview(deckId, "级联", updatedAt = 2L)

        dao.deleteDeck(requireNotNull(dao.getDeck(deckId)))

        assertThat(dao.getCard(cardId)).isNull()
        assertThat(dao.getReviewState(cardId)).isNull()
    }

    @Test
    fun missingRowsCannotBeUpdatedOrDeletedSilently() = runTest {
        val missingDeck = DeckEntity(id = 404L, name = "不存在", createdAt = 1L, updatedAt = 1L)
        val missingCard = card(deckId = 404L, key = "不存在", updatedAt = 1L).copy(id = 404L)

        val operations = listOf<suspend () -> Unit>(
            { dao.updateDeck(missingDeck) },
            { dao.updateCard(missingCard) },
            { dao.deleteDeck(missingDeck) },
            { dao.deleteCard(missingCard) },
        )

        operations.forEach { operation ->
            val error = runCatching { operation() }.exceptionOrNull()
            assertThat(error).isInstanceOf(IllegalStateException::class.java)
            assertThat(error).hasMessageThat().contains("404")
        }
    }

    private suspend fun insertCardWithReview(deckId: Long, key: String, updatedAt: Long): Long {
        return dao.insertCardWithReview(card(deckId, key, updatedAt))
    }

    private fun card(deckId: Long, key: String, updatedAt: Long) = CardEntity(
        deckId = deckId,
        keyText = key,
        valueImagePath = "images/$key.png",
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )
}
