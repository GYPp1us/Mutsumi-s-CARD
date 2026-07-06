package com.mutsumi.card.backup

import kotlinx.serialization.Serializable

@Serializable
data class BackupManifest(
    val version: Int,
    val exportedAt: Long,
    val appVersion: String,
    val deckCount: Int,
    val cardCount: Int,
)

@Serializable
data class BackupSnapshot(
    val decks: List<BackupDeck>,
    val cards: List<BackupCard>,
    val reviews: List<BackupReviewState>,
)

@Serializable
data class BackupDeck(val id: Long, val name: String, val createdAt: Long, val updatedAt: Long)

@Serializable
data class BackupCard(
    val id: Long,
    val deckId: Long,
    val keyText: String,
    val valueImagePath: String,
    val baseImagePath: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val archived: Boolean,
)

@Serializable
data class BackupReviewState(
    val cardId: Long,
    val weight: Double,
    val seenCount: Int,
    val againCount: Int,
    val unsureCount: Int,
    val knownCount: Int,
    val lastReviewedAt: Long?,
)

