package com.mutsumi.card.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudSyncPlannerTest {
    private val hash = "a".repeat(64)
    private fun document(vararg keys: String) = CloudSnapshotDocument(
        snapshot = CloudSnapshot(
            listOf(CloudDeckRecord("deck", "卡组", 1, 2)),
            keys.map { CloudCardRecord(it, "deck", it, 1, 2, false, hash) },
            keys.map { CloudReviewRecord(it, 1.0, 0, 0, 0, 0, null) },
        ), images = listOf(CloudImageReference(hash, 100)),
    )

    @Test fun `两个增量方向均保留目标独有卡片并只采用来源内容`() {
        val local = document("共同", "本地新增")
        val cloud = document("共同", "云端独有").let { it.copy(snapshot = it.snapshot.copy(
            cards = it.snapshot.cards.map { c -> c.copy(keyText = "云端${c.keyText}") },
        )) }
        val pull = CloudSyncPlanner.plan(CloudSyncMode.Pull, local, cloud)
        val push = CloudSyncPlanner.plan(CloudSyncMode.Push, local, cloud)
        assertThat(pull.document.snapshot.cards.map { it.keyText }).containsExactly("云端共同", "云端云端独有", "本地新增")
        assertThat(push.document.snapshot.cards.map { it.keyText }).containsExactly("共同", "云端云端独有", "本地新增")
        assertThat(pull.entries.none { it.change == CloudSyncChange.Delete }).isTrue()
        assertThat(push.entries.none { it.change == CloudSyncChange.Delete }).isTrue()
    }

    @Test fun `删除方向只删除缺席项不会更新共同卡片`() {
        val local = document("共同", "本地新增")
        val cloud = document("共同", "云端独有").let { it.copy(snapshot = it.snapshot.copy(
            cards = it.snapshot.cards.map { c -> c.copy(keyText = "云端${c.keyText}") },
        )) }
        val pull = CloudSyncPlanner.plan(CloudSyncMode.PullDelete, local, cloud)
        val push = CloudSyncPlanner.plan(CloudSyncMode.PushDelete, local, cloud)
        assertThat(pull.document.snapshot.cards.single().keyText).isEqualTo("共同")
        assertThat(push.document.snapshot.cards.single().keyText).isEqualTo("云端共同")
        assertThat(pull.entries.map { it.change }).containsExactly(CloudSyncChange.Delete)
        assertThat(push.entries.map { it.change }).containsExactly(CloudSyncChange.Delete)
    }

    @Test fun `预览包含复习更新和双面元数据并按页渐进读取`() {
        val local = document(*(1..85).map { "卡$it" }.toTypedArray())
        val plan = CloudSyncPlanner.plan(CloudSyncMode.Push, local, document())
        assertThat(plan.page(0).size).isEqualTo(40)
        assertThat(plan.page(40).size).isEqualTo(40)
        assertThat(plan.page(80).size).isEqualTo(5)
        assertThat(plan.page(200)).isEmpty()
        assertThat(plan.entries.first { it.kind == CloudConflictKind.Card }.imageBytes).isEqualTo(100)
    }

    @Test fun `卡组循环及重复卡片在预览阶段拒绝`() {
        val bad = document("卡").let { it.copy(snapshot = it.snapshot.copy(
            decks = listOf(CloudDeckRecord("deck", "卡组", 1, 2, parentSyncId = "deck")),
        )) }
        assertThrows(CloudBackupException::class.java) { CloudSyncPlanner.plan(CloudSyncMode.Pull, document(), bad) }
        val duplicated = document("卡", "卡")
        assertThrows(CloudBackupException::class.java) { CloudSyncPlanner.plan(CloudSyncMode.Pull, document(), duplicated) }
    }
}
