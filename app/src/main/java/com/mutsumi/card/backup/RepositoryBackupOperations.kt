package com.mutsumi.card.backup

import com.mutsumi.card.data.CardRepository
import com.mutsumi.card.data.image.CardImageStore
import com.mutsumi.card.domain.review.ReviewFeedback
import kotlinx.coroutines.flow.first
import java.io.File

/** 将公开 Repository 契约装配成可移植备份；导入始终创建新副本。 */
class RepositoryBackupOperations(
    private val repository: CardRepository,
    private val imageStore: CardImageStore,
    temporaryDirectory: File,
    private val now: () -> Long = System::currentTimeMillis,
) : BackupOperations {
    private val operations = ValidatedBackupOperations(
        snapshotSource = BackupSnapshotSource { loadExportData() },
        service = BackupService(),
        validator = BackupValidator(),
        importer = BackupImporter(ImportGateway { importBatch(it) }),
        temporaryDirectory = temporaryDirectory,
    )

    override suspend fun export(output: java.io.OutputStream): ExportSummary = operations.export(output)

    override suspend fun import(input: java.io.InputStream): ImportSummary = operations.import(input)

    internal suspend fun loadExportData(): BackupExportData {
        val timestamp = now()
        val decks = repository.decks.first()
        val cards = decks.flatMap { repository.cards(it.id).first() }
        val snapshot = BackupSnapshot(
            decks = decks.map { BackupDeck(it.id, it.name, timestamp, timestamp) },
            cards = cards.map {
                BackupCard(it.id, it.deckId, it.keyText, it.valueImagePath, timestamp, timestamp, it.archived)
            },
            reviews = cards.map {
                BackupReviewState(
                    it.id, it.review.weight, it.review.seenCount, it.review.againCount,
                    it.review.unsureCount, it.review.knownCount, it.review.lastReviewedAt,
                )
            },
        )
        return BackupExportData(
            snapshot = snapshot,
            images = cards.associate { it.valueImagePath to imageStore.resolve(it.valueImagePath) },
        )
    }

    internal suspend fun importBatch(batch: ImportBatch): ImportSummary {
        val deckIds = mutableMapOf<Long, Long>()
        batch.snapshot.decks.forEach { deck -> deckIds[deck.id] = repository.createDeck(deck.name) }
        val cardIds = mutableMapOf<Long, Long>()
        batch.snapshot.cards.forEach { card ->
            val source = requireNotNull(batch.images[card.valueImagePath]) { "备份图片缺失：${card.valueImagePath}" }
            val targetDeck = requireNotNull(deckIds[card.deckId]) { "备份卡组映射缺失：${card.deckId}" }
            cardIds[card.id] = repository.saveCard(targetDeck, card.keyText, source.readBytes())
        }
        batch.snapshot.reviews.forEach { review ->
            val cardId = requireNotNull(cardIds[review.cardId]) { "备份卡片映射缺失：${review.cardId}" }
            repeat(review.againCount) { repository.applyFeedback(cardId, ReviewFeedback.Again, now()) }
            repeat(review.unsureCount) { repository.applyFeedback(cardId, ReviewFeedback.Unsure, now()) }
            repeat(review.knownCount) { repository.applyFeedback(cardId, ReviewFeedback.Know, now()) }
        }
        return ImportSummary(deckIds.size, cardIds.size)
    }
}
