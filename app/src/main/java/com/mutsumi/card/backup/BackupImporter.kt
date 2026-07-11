package com.mutsumi.card.backup

import java.util.UUID
import java.io.File
import kotlin.random.Random

data class ImportBatch(
    val snapshot: BackupSnapshot,
    val images: Map<String, File>,
)

data class ImportSummary(
    val deckCount: Int,
    val cardCount: Int,
    val warnings: List<String> = emptyList(),
)

/**
 * 必须在一个数据库事务中写入整个批次。实现方还必须在数据库失败时清理本批次图片。
 */
fun interface ImportGateway {
    suspend fun importAtomically(batch: ImportBatch): ImportSummary
}

class BackupImporter(
    private val gateway: ImportGateway,
    private val idGenerator: () -> Long = { Random.nextLong(1, Long.MAX_VALUE) },
    private val imageNameGenerator: () -> String = { "value-${UUID.randomUUID()}.png" },
) {
    suspend fun importCopy(archive: ValidatedArchive): ImportSummary {
        val usedIds = mutableSetOf<Long>()
        fun newId(): Long = idGenerator().also { id ->
            check(id > 0 && usedIds.add(id)) { "导入 ID 生成器产生无效或重复 ID：$id" }
        }

        val deckIds = archive.snapshot.decks.associate { it.id to newId() }
        val cardIds = archive.snapshot.cards.associate { it.id to newId() }
        val usedImagePaths = mutableSetOf<String>()
        val imagePaths = archive.snapshot.cards.associate { card ->
            val path = "images/${imageNameGenerator()}"
            requireSafeImagePath(path)
            check(usedImagePaths.add(path)) { "导入图片名生成器产生重复路径：$path" }
            card.valueImagePath to path
        }

        val snapshot = BackupSnapshot(
            decks = archive.snapshot.decks.map { it.copy(id = deckIds.getValue(it.id)) },
            cards = archive.snapshot.cards.map { card ->
                card.copy(
                    id = cardIds.getValue(card.id),
                    deckId = deckIds.getValue(card.deckId),
                    valueImagePath = imagePaths.getValue(card.valueImagePath),
                )
            },
            reviews = archive.snapshot.reviews.map { review ->
                review.copy(cardId = cardIds.getValue(review.cardId))
            },
        )
        val images = archive.images.mapKeys { (oldPath, _) -> imagePaths.getValue(oldPath) }
        return gateway.importAtomically(ImportBatch(snapshot, images))
    }
}
