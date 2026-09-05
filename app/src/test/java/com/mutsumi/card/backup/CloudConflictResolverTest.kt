package com.mutsumi.card.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CloudConflictResolverTest {
    @Test
    fun `首同步空基线会保留仅本地新增`() {
        val local = snapshot(keyText = "本地新增", deckName = "本地卡组", reviewCount = 1)

        val merged = mergeFromEmptyBaseline(local = local, cloud = emptySnapshot())

        assertThat(merged).isEqualTo(local)
    }

    @Test
    fun `首同步空基线会保留仅云端新增`() {
        val cloud = snapshot(keyText = "云端新增", deckName = "云端卡组", reviewCount = 1)

        val merged = mergeFromEmptyBaseline(local = emptySnapshot(), cloud = cloud)

        assertThat(merged).isEqualTo(cloud)
    }

    @Test
    fun `首同步空基线会合并两侧独立新增`() {
        val local = snapshot(
            keyText = "本地新增",
            deckName = "本地卡组",
            reviewCount = 1,
            deckSyncId = "deck-local",
            cardSyncId = "card-local",
        )
        val cloud = snapshot(
            keyText = "云端新增",
            deckName = "云端卡组",
            reviewCount = 1,
            deckSyncId = "deck-cloud",
            cardSyncId = "card-cloud",
        )
        val baseline = emptySnapshot()

        val conflicts = CloudConflictResolver.findConflicts(baseline, local, cloud)
        val merged = mergeFromEmptyBaseline(local = local, cloud = cloud)

        assertThat(conflicts).isEmpty()
        assertThat(merged.cards.map(CloudCardRecord::keyText)).containsExactly("本地新增", "云端新增")
    }

    @Test
    fun `首同步空基线同稳定ID相同新增不冲突`() {
        val shared = snapshot(keyText = "相同", deckName = "相同卡组", reviewCount = 1)
        val baseline = emptySnapshot()

        val conflicts = CloudConflictResolver.findConflicts(baseline, shared, shared)
        val merged = CloudConflictResolver.merge(
            baseline = baseline,
            local = shared,
            cloud = shared,
            defaultResolution = CloudConflictResolution.KeepLocal,
            deleteExtras = false,
            conflicts = conflicts,
            conflictResolution = null,
        )

        assertThat(conflicts).isEmpty()
        assertThat(merged).isEqualTo(shared)
    }

    @Test
    fun `首同步空基线同稳定ID不同新增会检测冲突`() {
        val baseline = emptySnapshot()
        val local = snapshot(keyText = "本地版本", deckName = "本地卡组", reviewCount = 1)
        val cloud = snapshot(keyText = "云端版本", deckName = "云端卡组", reviewCount = 1)

        val conflicts = CloudConflictResolver.findConflicts(baseline, local, cloud)

        assertThat(conflicts.map(CloudConflict::kind))
            .containsExactly(CloudConflictKind.Deck, CloudConflictKind.Card)
            .inOrder()
    }

    @Test
    fun `推送不删除时保留仅在云端改动的既有记录`() {
        val baseline = snapshot(keyText = "原始", deckName = "默认", reviewCount = 1)
        val local = baseline
        val cloud = snapshot(keyText = "云端更新", deckName = "默认", reviewCount = 1)

        val conflicts = CloudConflictResolver.findConflicts(baseline, local, cloud)
        val merged = CloudConflictResolver.merge(
            baseline = baseline,
            local = local,
            cloud = cloud,
            defaultResolution = CloudConflictResolution.KeepLocal,
            deleteExtras = false,
            conflicts = conflicts,
            conflictResolution = null,
        )

        assertThat(conflicts).isEmpty()
        assertThat(merged.cards.single().keyText).isEqualTo("云端更新")
    }

    @Test
    fun `同一张卡片被两端改动时选择本地，同时保留独立云端改动`() {
        val baseline = snapshot(keyText = "原始", deckName = "默认", reviewCount = 1)
        val local = snapshot(keyText = "本地卡片", deckName = "默认", reviewCount = 1)
        val cloud = snapshot(keyText = "云端卡片", deckName = "云端卡组", reviewCount = 1)

        val conflicts = CloudConflictResolver.findConflicts(baseline, local, cloud)
        val merged = CloudConflictResolver.merge(
            baseline = baseline,
            local = local,
            cloud = cloud,
            defaultResolution = CloudConflictResolution.UseCloud,
            deleteExtras = false,
            conflicts = conflicts,
            conflictResolution = CloudConflictResolution.KeepLocal,
        )

        assertThat(conflicts).hasSize(1)
        assertThat(conflicts.single().kind).isEqualTo(CloudConflictKind.Card)
        assertThat(merged.cards.single().keyText).isEqualTo("本地卡片")
        assertThat(merged.decks.single().name).isEqualTo("云端卡组")
    }

    @Test
    fun `选择删除冲突卡组时会一并移除孤立卡片和复习状态`() {
        val baseline = snapshot(keyText = "原始", deckName = "默认", reviewCount = 1)
        val local = CloudSnapshot(emptyList(), emptyList(), emptyList())
        val cloud = snapshot(keyText = "原始", deckName = "云端改名", reviewCount = 1)

        val conflicts = CloudConflictResolver.findConflicts(baseline, local, cloud)
        val merged = CloudConflictResolver.merge(
            baseline = baseline,
            local = local,
            cloud = cloud,
            defaultResolution = CloudConflictResolution.UseCloud,
            deleteExtras = false,
            conflicts = conflicts,
            conflictResolution = CloudConflictResolution.KeepLocal,
        )

        assertThat(conflicts.single().kind).isEqualTo(CloudConflictKind.Deck)
        assertThat(merged.decks).isEmpty()
        assertThat(merged.cards).isEmpty()
        assertThat(merged.reviews).isEmpty()
    }

    private fun snapshot(
        keyText: String,
        deckName: String,
        reviewCount: Int,
        deckSyncId: String = "deck-1",
        cardSyncId: String = "card-1",
    ): CloudSnapshot {
        return CloudSnapshot(
            decks = listOf(CloudDeckRecord(deckSyncId, deckName, 1, 2)),
            cards = listOf(
                CloudCardRecord(
                    syncId = cardSyncId,
                    deckSyncId = deckSyncId,
                    keyText = keyText,
                    createdAt = 1,
                    updatedAt = 2,
                    archived = false,
                    valueImageSha256 = "a".repeat(64),
                ),
            ),
            reviews = listOf(CloudReviewRecord(cardSyncId, 1.0, reviewCount, 0, 0, 0, null)),
        )
    }

    private fun mergeFromEmptyBaseline(local: CloudSnapshot, cloud: CloudSnapshot): CloudSnapshot {
        val baseline = emptySnapshot()
        val conflicts = CloudConflictResolver.findConflicts(baseline, local, cloud)
        return CloudConflictResolver.merge(
            baseline = baseline,
            local = local,
            cloud = cloud,
            defaultResolution = CloudConflictResolution.KeepLocal,
            deleteExtras = false,
            conflicts = conflicts,
            conflictResolution = null,
        )
    }

    private fun emptySnapshot(): CloudSnapshot = CloudSnapshot(emptyList(), emptyList(), emptyList())
}
