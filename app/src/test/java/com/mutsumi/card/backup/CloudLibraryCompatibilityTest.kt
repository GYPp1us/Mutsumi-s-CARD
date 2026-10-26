package com.mutsumi.card.backup

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.mutsumi.card.data.image.FileCardImageStore
import com.mutsumi.card.data.image.PngDecoder
import com.mutsumi.card.data.image.ValuePngValidator
import com.mutsumi.card.data.local.MutsumiCardDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer
import java.util.Base64

/** 真实取证材料只在忽略目录，本测试在显式指定时验证，CI 不携带用户数据。 */
@RunWith(RobolectricTestRunner::class)
class CloudLibraryCompatibilityTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun `真实新索引完整保留旧卡并可通过新增量拉取导入十张恢复卡`() = runBlocking(Dispatchers.IO) { withTimeout(120_000) {
        val property = System.getProperty("mutsumi.libraryValidationRootBase64")
        assumeTrue("未提供真实恢复目录", !property.isNullOrBlank())
        val root = File(String(Base64.getDecoder().decode(property), Charsets.UTF_8))
        val staging = File(root, "v010-cloud-staging")
        val evidence = File(root, "cloud-recovery-20261025-r2/evidence")
        val indexBytes = File(staging, "candidate-library-index.json").readBytes()
        val index = Json.decodeFromString<CloudLibraryIndex>(indexBytes.decodeToString())
        CloudSyncPlanner.validate(index.document)
        assertThat(index.decks).isEqualTo(CloudSyncPlanner.deckIndex(index.document))
        val oldIndex = Json.decodeFromString<CloudBackupIndex>(File(staging, "legacy-index-live.json").readText())
        val old = Json.decodeFromString<CloudSnapshotDocument>(File(evidence, "snapshots/${oldIndex.snapshots.first().id}.json").readText())
        val objects = index.document.images.associate { ref -> ref.sha256 to File(evidence, "objects/${ref.sha256}.png").readBytes() }
        val database = MutsumiCardDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        try {
            val data = RoomCloudBackupDataAccess(database.cardDao(), FileCardImageStore(folder.root), indexFile = File(folder.root, "index.json"))
            val empty = data.readMetadata()
            data.applyPlan(old, old.images.associate { it.sha256 to objects.getValue(it.sha256) }, empty.revision, false)
            val decoder = ValuePngValidator(PngDecoder { bytes -> ByteBuffer.wrap(bytes, 16, 8).let { it.int to it.int } })
            val remote = object : CloudRemoteStore {
                override suspend fun ensureDirectories() = Unit
                override suspend fun get(path: String): ByteArray? = if (path == "library-index.json") indexBytes else
                    path.removePrefix("library-v3/").removePrefix("objects/").removeSuffix(".png").let { objects[it] }
                override suspend fun getWithVersion(path: String) = get(path)?.let { CloudRemoteFile(it, sha256(it)) }
                override suspend fun put(path: String, bytes: ByteArray, contentType: String) = error("拉取不允许写云端")
                override suspend fun putIfUnchanged(path: String, bytes: ByteArray, contentType: String, expectedVersion: String?) = error("拉取不允许写云端")
                override suspend fun delete(path: String) = error("不允许删除原件")
            }
            val service = DirectionalCloudSyncService(data, folder.root, remoteFactory = { remote }, pngValidator = decoder)
            val config = CloudBackupConfig("https://example.test", "test", "synthetic")
            val preview = service.preview(config, CloudSyncMode.Pull)
            assertThat(preview.plan.entries.filter { it.kind == CloudConflictKind.Card }.size).isEqualTo(10)
            assertThat(preview.plan.entries.all { it.change == CloudSyncChange.Add }).isTrue()
            service.execute(config, preview.token) {}
            val actual = data.readMetadata().document
            assertThat(actual.snapshot.cards.size).isEqualTo(41)
            assertThat(actual.snapshot.decks.size).isEqualTo(5)
            val byId = actual.snapshot.cards.associateBy { it.syncId }
            old.snapshot.cards.forEach { assertThat(byId[it.syncId]).isEqualTo(it) }
            val reviews = actual.snapshot.reviews.associateBy { it.cardSyncId }
            old.snapshot.reviews.forEach { assertThat(reviews[it.cardSyncId]).isEqualTo(it) }
            assertThat(service.preview(config, CloudSyncMode.Pull).plan.entries).isEmpty()
        } finally { database.close() }
    } }
}
