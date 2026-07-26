package com.mutsumi.card.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(
    tableName = "decks",
    indices = [Index("syncId")],
)
data class DeckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val syncId: String = "",
)

@Entity(
    tableName = "cards",
    foreignKeys = [
        ForeignKey(
            entity = DeckEntity::class,
            parentColumns = ["id"],
            childColumns = ["deckId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("deckId"),
        Index("syncId"),
    ],
)
data class CardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deckId: Long,
    val keyText: String,
    val valueImagePath: String,
    val createdAt: Long,
    val updatedAt: Long,
    val archived: Boolean = false,
    val frontImagePath: String? = null,
    val syncId: String = "",
)

@Entity(
    tableName = "review_states",
    foreignKeys = [
        ForeignKey(
            entity = CardEntity::class,
            parentColumns = ["id"],
            childColumns = ["cardId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ReviewStateEntity(
    @PrimaryKey val cardId: Long,
    val weight: Double = 1.0,
    val seenCount: Int = 0,
    val againCount: Int = 0,
    val unsureCount: Int = 0,
    val knownCount: Int = 0,
    val lastReviewedAt: Long? = null,
)

@Entity(tableName = "pending_image_deletions")
data class PendingImageDeletionEntity(
    @PrimaryKey val path: String,
    val queuedAt: Long,
)

data class CardWithReviewState(
    @Embedded val card: CardEntity,
    @Relation(parentColumn = "id", entityColumn = "cardId")
    val reviewState: ReviewStateEntity,
)

data class DeckWithCardCount(
    @Embedded val deck: DeckEntity,
    val cardCount: Int,
)

data class SyncApplyRows(
    val decksToInsert: List<DeckEntity>,
    val decksToUpdate: List<DeckEntity>,
    val cardsToInsert: List<CardEntity>,
    val cardsToUpdate: List<CardEntity>,
    val reviews: List<ReviewStateEntity>,
    val cardsToDelete: List<CardEntity>,
    val decksToDelete: List<DeckEntity>,
    val oldImagesToDelete: List<String>,
    val queuedAt: Long,
)
