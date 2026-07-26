package com.mutsumi.card.backup

import com.mutsumi.card.data.CardRepository
import com.mutsumi.card.data.image.CardImageStore
import com.mutsumi.card.domain.review.ReviewFeedback
import kotlinx.coroutines.flow.first
import java.io.File

interface CloudBackupDataAccess {
    suspend fun loadExportData(): BackupExportData
    suspend fun importBatch(batch: ImportBatch): ImportSummary
    suspend fun loadCloudState(): CloudLocalState {
        val export = loadExportData()
        val imageRefs = export.images.map { (path, file) ->
            val data = file.readBytes()
            CloudImageReference(sha256(data), data.size.toLong(), path)
        }
        val deckSync = export.snapshot.decks.associate { it.id to "legacy-deck-${it.id}" }
        val cardSync = export.snapshot.cards.associate { it.id to "legacy-card-${it.id}" }
        val snapshot = CloudSnapshot(
            decks = export.snapshot.decks.map {
                CloudDeckRecord(deckSync.getValue(it.id), it.name, it.createdAt, it.updatedAt)
            },
            cards = export.snapshot.cards.map {
                val valueBytes = export.images.getValue(it.valueImagePath).readBytes()
                CloudCardRecord(
                    syncId = cardSync.getValue(it.id),
                    deckSyncId = deckSync.getValue(it.deckId),
                    keyText = it.keyText,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                    archived = it.archived,
                    valueImageSha256 = sha256(valueBytes),
                    frontImageSha256 = it.frontImagePath?.let { path -> sha256(export.images.getValue(path).readBytes()) },
                )
            },
            reviews = export.snapshot.reviews.map {
                CloudReviewRecord(
                    cardSyncId = cardSync.getValue(it.cardId),
                    weight = it.weight,
                    seenCount = it.seenCount,
                    againCount = it.againCount,
                    unsureCount = it.unsureCount,
                    knownCount = it.knownCount,
                    lastReviewedAt = it.lastReviewedAt,
                )
            },
        )
        return CloudLocalState(
            CloudSnapshotDocument(snapshot = snapshot, images = imageRefs),
            export.images.values.associate { file -> sha256(file.readBytes()) to file.readBytes() },
        )
    }

    suspend fun applyCloudSnapshot(
        snapshot: CloudSnapshot,
        images: Map<String, ByteArray>,
        deleteExtras: Boolean,
        sourcePaths: Map<String, String> = emptyMap(),
    ): ImportSummary {
        val directory = File.createTempFile("mutsumi-cloud", "").apply {
            delete()
            mkdirs()
        }
        try {
            val imagePaths = images.mapValues { (hash, bytes) ->
                File(directory, "$hash.png").apply { writeBytes(bytes) }
            }
            val deckIds = snapshot.decks.mapIndexed { index, deck -> deck.syncId to index + 1L }.toMap()
            val cardIds = snapshot.cards.mapIndexed { index, card -> card.syncId to index + 1L }.toMap()
            val pathFor = { hash: String ->
                sourcePaths[hash]?.also(::requireSafeImagePath) ?: "images/$hash.png"
            }
            val localSnapshot = BackupSnapshot(
                decks = snapshot.decks.map { deck ->
                    BackupDeck(deckIds.getValue(deck.syncId), deck.name, deck.createdAt, deck.updatedAt)
                },
                cards = snapshot.cards.map { card ->
                    BackupCard(
                        id = cardIds.getValue(card.syncId),
                        deckId = deckIds.getValue(card.deckSyncId),
                        keyText = card.keyText,
                        valueImagePath = pathFor(card.valueImageSha256),
                        createdAt = card.createdAt,
                        updatedAt = card.updatedAt,
                        archived = card.archived,
                        frontImagePath = card.frontImageSha256?.let(pathFor),
                    )
                },
                reviews = snapshot.reviews.map { review ->
                    BackupReviewState(
                        cardId = cardIds.getValue(review.cardSyncId),
                        weight = review.weight,
                        seenCount = review.seenCount,
                        againCount = review.againCount,
                        unsureCount = review.unsureCount,
                        knownCount = review.knownCount,
                        lastReviewedAt = review.lastReviewedAt,
                    )
                },
            )
            val files = imagePaths.mapKeys { pathFor(it.key) }
            return importBatch(ImportBatch(localSnapshot, files))
        } finally {
            check(!directory.exists() || directory.deleteRecursively()) { "无法清理云端兼容临时目录" }
        }
    }
}

/** 将公开 Repository 契约装配成可移植备份；导入始终创建新副本。 */
class RepositoryBackupOperations(
    private val repository: CardRepository,
    private val imageStore: CardImageStore,
    temporaryDirectory: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val cloudDataAccess: CloudBackupDataAccess? = null,
) : BackupOperations, CloudBackupDataAccess {
    private val operations = ValidatedBackupOperations(
        snapshotSource = BackupSnapshotSource { loadExportData() },
        service = BackupService(),
        validator = BackupValidator(),
        importer = BackupImporter(ImportGateway { importBatch(it) }),
        temporaryDirectory = temporaryDirectory,
    )

    override suspend fun export(output: java.io.OutputStream): ExportSummary = operations.export(output)

    override suspend fun import(input: java.io.InputStream): ImportSummary = operations.import(input)

    override suspend fun loadCloudState(): CloudLocalState =
        requireNotNull(cloudDataAccess) { "当前未配置云同步数据访问层" }.loadCloudState()

    override suspend fun applyCloudSnapshot(
        snapshot: CloudSnapshot,
        images: Map<String, ByteArray>,
        deleteExtras: Boolean,
        sourcePaths: Map<String, String>,
    ): ImportSummary = requireNotNull(cloudDataAccess) { "当前未配置云同步数据访问层" }
        .applyCloudSnapshot(snapshot, images, deleteExtras, sourcePaths)

    override suspend fun loadExportData(): BackupExportData {
        val timestamp = now()
        val decks = repository.decks.first()
        val cards = decks.flatMap { repository.cards(it.id).first() }
        val snapshot = BackupSnapshot(
            decks = decks.map { BackupDeck(it.id, it.name, timestamp, timestamp) },
            cards = cards.map {
                BackupCard(
                    it.id, it.deckId, it.keyText, it.valueImagePath, timestamp, timestamp, it.archived,
                    frontImagePath = it.frontImagePath,
                )
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
            images = cards.flatMap { card ->
                buildList {
                    add(card.valueImagePath)
                    card.frontImagePath?.let(::add)
                }
            }.associateWith(imageStore::resolve),
        )
    }

    override suspend fun importBatch(batch: ImportBatch): ImportSummary {
        val deckIds = mutableMapOf<Long, Long>()
        batch.snapshot.decks.forEach { deck -> deckIds[deck.id] = repository.createDeck(deck.name) }
        val cardIds = mutableMapOf<Long, Long>()
        batch.snapshot.cards.forEach { card ->
            val source = requireNotNull(batch.images[card.valueImagePath]) { "备份图片缺失：${card.valueImagePath}" }
            val targetDeck = requireNotNull(deckIds[card.deckId]) { "备份卡组映射缺失：${card.deckId}" }
            val front = card.frontImagePath?.let { path ->
                requireNotNull(batch.images[path]) { "备份正面图片缺失：$path" }.readBytes()
            }
            cardIds[card.id] = repository.saveCard(targetDeck, card.keyText, front, source.readBytes())
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
