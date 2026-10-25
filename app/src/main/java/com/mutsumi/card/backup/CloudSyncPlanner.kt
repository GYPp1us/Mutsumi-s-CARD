package com.mutsumi.card.backup

import kotlinx.serialization.Serializable

enum class CloudSyncMode(val label: String, val isPull: Boolean, val deletes: Boolean) {
    Pull("增量拉取", true, false), Push("增量推送", false, false),
    PullDelete("拉取删除", true, true), PushDelete("推送删除", false, true),
}

enum class CloudSyncChange(val label: String) { Add("新增"), Update("更新"), Delete("删除") }

data class CloudSyncEntry(
    val id: String, val kind: CloudConflictKind, val change: CloudSyncChange,
    val title: String, val location: String, val createdAt: Long, val updatedAt: Long,
    val archived: Boolean, val seenCount: Int = 0, val doubleSided: Boolean = false,
    val imageBytes: Long = 0, val previousTitle: String? = null,
    val details: List<String> = emptyList(),
)

data class CloudSyncPlan(val document: CloudSnapshotDocument, val entries: List<CloudSyncEntry>) {
    fun page(offset: Int, size: Int = 40): List<CloudSyncEntry> = entries.drop(offset.coerceAtLeast(0)).take(size.coerceIn(1, 100))
}

@Serializable
data class CloudDeckIndex(
    val syncId: String, val name: String, val parentSyncId: String?, val archived: Boolean,
    val cardCount: Int, val archivedCardCount: Int, val imageBytes: Long, val updatedAt: Long,
)

/** 单文件就是当前数据库的完整元数据索引；PNG 留在内容寻址对象目录。 */
@Serializable
data class CloudLibraryIndex(
    val formatVersion: Int = 3,
    val revision: String,
    val updatedAt: Long,
    val document: CloudSnapshotDocument,
    val decks: List<CloudDeckIndex>,
    val snapshots: List<CloudSnapshotSummary> = emptyList(),
)

object CloudSyncPlanner {
    private val empty = CloudSnapshot(emptyList(), emptyList(), emptyList())

    fun plan(mode: CloudSyncMode, local: CloudSnapshotDocument, cloud: CloudSnapshotDocument): CloudSyncPlan {
        validate(local); validate(cloud)
        val source = if (mode.isPull) cloud else local
        val target = if (mode.isPull) local else cloud
        val from = source.snapshot
        val into = target.snapshot
        val result = if (!mode.deletes) {
            CloudSnapshot(
                merge(into.decks, from.decks) { it.syncId },
                merge(into.cards, from.cards) { it.syncId },
                merge(into.reviews, from.reviews) { it.cardSyncId },
            )
        } else {
            val sourceCards = from.cards.mapTo(hashSetOf()) { it.syncId }
            val cards = into.cards.filter { it.syncId in sourceCards }
            val neededDecks = (from.decks.map { it.syncId } + cards.map { it.deckSyncId }).toMutableSet()
            val decksById = into.decks.associateBy { it.syncId }
            // 删除操作不移动仍保留的卡片，也不切断其父级。
            neededDecks.toList().forEach { id ->
                var parent = decksById[id]?.parentSyncId
                while (parent != null && neededDecks.add(parent)) parent = decksById[parent]?.parentSyncId
            }
            CloudSnapshot(into.decks.filter { it.syncId in neededDecks }, cards,
                into.reviews.filter { it.cardSyncId in sourceCards })
        }
        val refs = (target.images + source.images).associateBy { it.sha256 }
        val hashes = result.cards.flatMap { listOfNotNull(it.valueImageSha256, it.frontImageSha256) }.toSet()
        val document = CloudSnapshotDocument(snapshot = result, images = hashes.sorted().map { refs.getValue(it) }.map { it.copy(sourcePath = null) })
        validate(document)
        val oldDecks = into.decks.associateBy { it.syncId }
        val newDecks = result.decks.associateBy { it.syncId }
        val oldCards = into.cards.associateBy { it.syncId }
        val newCards = result.cards.associateBy { it.syncId }
        val oldReviews = into.reviews.associateBy { it.cardSyncId }
        val newReviews = result.reviews.associateBy { it.cardSyncId }
        val names = (into.decks + result.decks).associate { it.syncId to it.name }
        val entries = buildList {
            (oldDecks.keys + newDecks.keys).forEach { id ->
                val old = oldDecks[id]; val new = newDecks[id]
                if (old != new) {
                    val row = requireNotNull(new ?: old)
                    add(CloudSyncEntry(id, CloudConflictKind.Deck, change(old, new), row.name,
                        row.parentSyncId?.let { names[it] }.orEmpty(), row.createdAt, row.updatedAt, row.archived,
                        previousTitle = old?.name?.takeIf { it != row.name }, details = buildList {
                            if (old != null && new != null) {
                                if (old.parentSyncId != new.parentSyncId) add("位置：${old.parentSyncId?.let { names[it] } ?: "根目录"} → ${new.parentSyncId?.let { names[it] } ?: "根目录"}")
                                if (old.archived != new.archived) add(if (new.archived) "将归档" else "将恢复使用")
                            }
                        }))
                }
            }
            (oldCards.keys + newCards.keys).forEach { id ->
                val old = oldCards[id]; val new = newCards[id]
                if (old != new || oldReviews[id] != newReviews[id]) {
                    val row = requireNotNull(new ?: old)
                    add(CloudSyncEntry(id, CloudConflictKind.Card, change(old, new), row.keyText,
                        names[row.deckSyncId].orEmpty(), row.createdAt, row.updatedAt, row.archived,
                        (newReviews[id] ?: oldReviews[id])?.seenCount ?: 0, row.frontImageSha256 != null,
                        listOfNotNull(row.valueImageSha256, row.frontImageSha256).distinct().sumOf { refs.getValue(it).size },
                        old?.keyText?.takeIf { it != row.keyText }, details = buildList {
                            if (old != null && new != null) {
                                if (old.deckSyncId != new.deckSyncId) add("卡组：${names[old.deckSyncId]} → ${names[new.deckSyncId]}")
                                if (old.valueImageSha256 != new.valueImageSha256) add("背面卡面更换")
                                if (old.frontImageSha256 != new.frontImageSha256) add("正面卡面${if (new.frontImageSha256 == null) "移除" else "更换"}")
                                if (old.archived != new.archived) add(if (new.archived) "将归档" else "将恢复使用")
                                if (oldReviews[id] != newReviews[id]) add("复习：${oldReviews[id]?.seenCount ?: 0} → ${newReviews[id]?.seenCount ?: 0} 次；权重 ${oldReviews[id]?.weight} → ${newReviews[id]?.weight}")
                            }
                        }))
                }
            }
        }
        return CloudSyncPlan(document, entries)
    }

    fun deckIndex(document: CloudSnapshotDocument): List<CloudDeckIndex> {
        val cards = document.snapshot.cards.groupBy { it.deckSyncId }
        val refs = document.images.associateBy { it.sha256 }
        return document.snapshot.decks.map { deck ->
            val rows = cards[deck.syncId].orEmpty()
            CloudDeckIndex(deck.syncId, deck.name, deck.parentSyncId, deck.archived,
                rows.count { !it.archived }, rows.count { it.archived },
                rows.flatMap { listOfNotNull(it.valueImageSha256, it.frontImageSha256) }.distinct().sumOf { refs.getValue(it).size },
                maxOf(deck.updatedAt, rows.maxOfOrNull { it.updatedAt } ?: 0))
        }
    }

    fun validate(document: CloudSnapshotDocument) {
        fun requireValid(value: Boolean, message: String) { if (!value) throw CloudBackupException(message) }
        val snapshot = document.snapshot
        val decks = snapshot.decks.associateBy { it.syncId }
        val cards = snapshot.cards.associateBy { it.syncId }
        val images = document.images.associateBy { it.sha256 }
        requireValid(decks.size == snapshot.decks.size && cards.size == snapshot.cards.size, "索引包含重复 ID")
        requireValid(snapshot.decks.all { it.syncId.isNotBlank() && it.name.isNotBlank() }, "卡组元数据不完整")
        decks.values.forEach { row ->
            val visited = hashSetOf(row.syncId)
            var parent = row.parentSyncId
            while (parent != null) {
                requireValid(parent in decks && visited.add(parent), "卡组层级存在循环或父级缺失")
                parent = decks.getValue(parent).parentSyncId
            }
        }
        val used = snapshot.cards.flatMap { listOfNotNull(it.valueImageSha256, it.frontImageSha256) }.toSet()
        requireValid(snapshot.cards.all { it.syncId.isNotBlank() && it.keyText.isNotBlank() && it.deckSyncId in decks }, "卡片元数据或卡组引用无效")
        requireValid(used.all { it in images } && images.values.all { Regex("[0-9a-f]{64}").matches(it.sha256) && it.size > 0 }, "图片元数据无效或缺失")
        requireValid(snapshot.reviews.map { it.cardSyncId }.toSet() == cards.keys && snapshot.reviews.size == cards.size, "复习记录缺失或重复")
        requireValid(snapshot.decks.all { it.createdAt >= 0 && it.updatedAt >= 0 } && snapshot.cards.all { it.createdAt >= 0 && it.updatedAt >= 0 }, "创建或更新时间无效")
        requireValid(snapshot.reviews.all { it.weight.isFinite() && it.weight in 0.2..5.0 && it.seenCount >= 0 &&
            it.againCount >= 0 && it.unsureCount >= 0 && it.knownCount >= 0 && it.seenCount.toLong() == it.againCount.toLong() + it.unsureCount + it.knownCount &&
            (it.lastReviewedAt == null || it.lastReviewedAt >= 0) }, "复习记录无效")
    }

    private fun <T> merge(target: List<T>, source: List<T>, key: (T) -> String): List<T> =
        (target + source).associateBy(key).values.toList()
    private fun change(old: Any?, new: Any?) = when { old == null -> CloudSyncChange.Add; new == null -> CloudSyncChange.Delete; else -> CloudSyncChange.Update }
}
