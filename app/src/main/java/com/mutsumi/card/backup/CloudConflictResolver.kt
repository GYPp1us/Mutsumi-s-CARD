package com.mutsumi.card.backup

/** Three-way conflict detection and deterministic snapshot composition for cloud sync. */
internal object CloudConflictResolver {
    fun findConflicts(
        baseline: CloudSnapshot,
        local: CloudSnapshot,
        cloud: CloudSnapshot,
    ): List<CloudConflict> {
        return buildList {
            addAll(
                findConflicts(
                    kind = CloudConflictKind.Deck,
                    baseline = index(baseline.decks, CloudDeckRecord::syncId),
                    local = index(local.decks, CloudDeckRecord::syncId),
                    cloud = index(cloud.decks, CloudDeckRecord::syncId),
                    summary = ::deckSummary,
                ),
            )
            addAll(
                findConflicts(
                    kind = CloudConflictKind.Card,
                    baseline = index(baseline.cards, CloudCardRecord::syncId),
                    local = index(local.cards, CloudCardRecord::syncId),
                    cloud = index(cloud.cards, CloudCardRecord::syncId),
                    summary = ::cardSummary,
                ),
            )
            addAll(
                findConflicts(
                    kind = CloudConflictKind.Review,
                    baseline = index(baseline.reviews, CloudReviewRecord::cardSyncId),
                    local = index(local.reviews, CloudReviewRecord::cardSyncId),
                    cloud = index(cloud.reviews, CloudReviewRecord::cardSyncId),
                    summary = ::reviewSummary,
                ),
            )
        }
    }

    /**
     * Keeps independent edits from both sides, while applying [conflictResolution]
     * only to true three-way conflicts. When deletion mirroring is enabled,
     * [deleteExtras] makes the default side authoritative for absent records.
     */
    fun merge(
        baseline: CloudSnapshot?,
        local: CloudSnapshot,
        cloud: CloudSnapshot,
        defaultResolution: CloudConflictResolution,
        deleteExtras: Boolean,
        conflicts: List<CloudConflict>,
        conflictResolution: CloudConflictResolution?,
    ): CloudSnapshot {
        val conflictKeys = conflicts.mapTo(linkedSetOf(), CloudConflict::key)
        require(conflictKeys.isEmpty() || conflictResolution != null) { "检测到同步冲突，必须先选择保留本地或采用云端" }

        val localDecks = index(local.decks, CloudDeckRecord::syncId)
        val cloudDecks = index(cloud.decks, CloudDeckRecord::syncId)
        val baselineDecks = baseline?.let { index(it.decks, CloudDeckRecord::syncId) }.orEmpty()
        val localCards = index(local.cards, CloudCardRecord::syncId)
        val cloudCards = index(cloud.cards, CloudCardRecord::syncId)
        val baselineCards = baseline?.let { index(it.cards, CloudCardRecord::syncId) }.orEmpty()
        val localReviews = index(local.reviews, CloudReviewRecord::cardSyncId)
        val cloudReviews = index(cloud.reviews, CloudReviewRecord::cardSyncId)
        val baselineReviews = baseline?.let { index(it.reviews, CloudReviewRecord::cardSyncId) }.orEmpty()

        val decks = mergeRecords(
            kind = CloudConflictKind.Deck,
            baseline = baselineDecks,
            local = localDecks,
            cloud = cloudDecks,
            defaultResolution = defaultResolution,
            deleteExtras = deleteExtras,
            conflictKeys = conflictKeys,
            conflictResolution = conflictResolution,
        ).toMutableList()
        val forcedDeletedDeckIds = forcedDeletedIds(
            kind = CloudConflictKind.Deck,
            local = localDecks,
            cloud = cloudDecks,
            conflictKeys = conflictKeys,
            conflictResolution = conflictResolution,
        )

        val cards = mergeRecords(
            kind = CloudConflictKind.Card,
            baseline = baselineCards,
            local = localCards,
            cloud = cloudCards,
            defaultResolution = defaultResolution,
            deleteExtras = deleteExtras,
            conflictKeys = conflictKeys,
            conflictResolution = conflictResolution,
        ).filterNot { it.deckSyncId in forcedDeletedDeckIds }
        val availableDeckIds = decks.mapTo(mutableSetOf(), CloudDeckRecord::syncId)
        cards.forEach { card ->
            if (card.deckSyncId !in availableDeckIds) {
                val parentDeck = localDecks[card.deckSyncId] ?: cloudDecks[card.deckSyncId]
                    ?: error("同步卡片引用了不存在的卡组：${card.syncId}")
                decks += parentDeck
                availableDeckIds += parentDeck.syncId
            }
        }

        val cardIds = cards.mapTo(mutableSetOf(), CloudCardRecord::syncId)
        val reviews = mergeRecords(
            kind = CloudConflictKind.Review,
            baseline = baselineReviews,
            local = localReviews,
            cloud = cloudReviews,
            defaultResolution = defaultResolution,
            deleteExtras = deleteExtras,
            conflictKeys = conflictKeys,
            conflictResolution = conflictResolution,
        ).filter { it.cardSyncId in cardIds }

        return CloudSnapshot(
            decks = decks,
            cards = cards,
            reviews = reviews,
        )
    }

    private fun <T> findConflicts(
        kind: CloudConflictKind,
        baseline: Map<String, T>,
        local: Map<String, T>,
        cloud: Map<String, T>,
        summary: (T?) -> String,
    ): List<CloudConflict> {
        return (baseline.keys + local.keys + cloud.keys).toSortedSet().mapNotNull { id ->
            val baselineRecord = baseline[id]
            val localRecord = local[id]
            val cloudRecord = cloud[id]
            if (
                localRecord != baselineRecord &&
                cloudRecord != baselineRecord &&
                localRecord != cloudRecord
            ) {
                CloudConflict(
                    key = key(kind, id),
                    kind = kind,
                    localSummary = summary(localRecord),
                    cloudSummary = summary(cloudRecord),
                    fingerprint = "${baselineRecord}|${localRecord}|${cloudRecord}",
                )
            } else {
                null
            }
        }
    }

    private fun <T> mergeRecords(
        kind: CloudConflictKind,
        baseline: Map<String, T>,
        local: Map<String, T>,
        cloud: Map<String, T>,
        defaultResolution: CloudConflictResolution,
        deleteExtras: Boolean,
        conflictKeys: Set<String>,
        conflictResolution: CloudConflictResolution?,
    ): List<T> {
        return (baseline.keys + local.keys + cloud.keys).toSortedSet().mapNotNull { id ->
            val conflictKey = key(kind, id)
            when {
                conflictKey in conflictKeys -> recordFor(conflictResolution!!, local[id], cloud[id])
                deleteExtras -> recordFor(defaultResolution, local[id], cloud[id])
                local[id] == baseline[id] && cloud[id] != baseline[id] -> cloud[id]
                cloud[id] == baseline[id] && local[id] != baseline[id] -> local[id]
                local[id] == cloud[id] -> local[id]
                else -> recordFor(defaultResolution, local[id], cloud[id])
                    ?: recordFor(other(defaultResolution), local[id], cloud[id])
            }
        }
    }

    private fun <T> forcedDeletedIds(
        kind: CloudConflictKind,
        local: Map<String, T>,
        cloud: Map<String, T>,
        conflictKeys: Set<String>,
        conflictResolution: CloudConflictResolution?,
    ): Set<String> {
        val resolution = conflictResolution ?: return emptySet()
        return (local.keys + cloud.keys).filterTo(mutableSetOf()) { id ->
            val conflictKey = key(kind, id)
            conflictKey in conflictKeys && recordFor(resolution, local[id], cloud[id]) == null
        }
    }

    private fun <T> index(records: List<T>, id: (T) -> String): Map<String, T> {
        val indexed = records.associateBy(id)
        require(indexed.size == records.size) { "云同步记录存在重复稳定 ID" }
        return indexed
    }

    private fun <T> recordFor(
        resolution: CloudConflictResolution,
        local: T?,
        cloud: T?,
    ): T? = when (resolution) {
        CloudConflictResolution.KeepLocal -> local
        CloudConflictResolution.UseCloud -> cloud
    }

    private fun other(resolution: CloudConflictResolution): CloudConflictResolution = when (resolution) {
        CloudConflictResolution.KeepLocal -> CloudConflictResolution.UseCloud
        CloudConflictResolution.UseCloud -> CloudConflictResolution.KeepLocal
    }

    private fun key(kind: CloudConflictKind, id: String): String = "${kind.name.lowercase()}:$id"

    private fun deckSummary(record: CloudDeckRecord?): String = record?.name ?: "已删除"

    private fun cardSummary(record: CloudCardRecord?): String = record?.let {
        "${it.keyText}（${it.updatedAt}）"
    } ?: "已删除"

    private fun reviewSummary(record: CloudReviewRecord?): String = record?.let {
        "复习 ${it.seenCount} 次"
    } ?: "已删除"
}
