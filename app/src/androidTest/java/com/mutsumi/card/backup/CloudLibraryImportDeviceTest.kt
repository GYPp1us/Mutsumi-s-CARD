package com.mutsumi.card.backup

import androidx.test.platform.app.InstrumentationRegistry
import com.mutsumi.card.data.image.FileCardImageStore
import com.mutsumi.card.data.local.MutsumiCardDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** 用户原件仅经显式参数进入独立设备，公开测试包不包含真实卡片或凭据。 */
class CloudLibraryImportDeviceTest {
    @Test fun 原生设备逐张增量拉取恢复卡且保留未上传卡片() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val requested = InstrumentationRegistry.getArguments().getString("recoveryFixture")
        assumeTrue("未显式提供私有恢复材料", !requested.isNullOrBlank())
        val context = instrumentation.targetContext
        val root = File(requireNotNull(requested)).canonicalFile
        require(root.toPath().startsWith(requireNotNull(context.getExternalFilesDir(null)).canonicalFile.toPath()))
        val indexBytes = File(root,"library-index.json").readBytes()
        val index = Json.decodeFromString<CloudLibraryIndex>(indexBytes.decodeToString())
        val old = Json.decodeFromString<CloudSnapshotDocument>(File(root,"prior-snapshot.json").readText())
        val first = old.snapshot.cards.first()
        val extra = first.copy(syncId="device-only-card",keyText="设备未上传测试卡",createdAt=100,updatedAt=100)
        val baseline = old.copy(snapshot=old.snapshot.copy(cards=old.snapshot.cards+extra,
            reviews=old.snapshot.reviews+CloudReviewRecord(extra.syncId,1.0,0,0,0,0,null)))
        val temp = File(context.cacheDir,"recovery-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val database = MutsumiCardDatabase.inMemory(context)
        try {
            val data = RoomCloudBackupDataAccess(database.cardDao(),FileCardImageStore(temp),indexFile=File(temp,"index.json"))
            data.applyPlan(baseline,old.images.associate { it.sha256 to File(root,"objects/${it.sha256}.png").readBytes() },data.readMetadata().revision,false)
            val remote = object:CloudRemoteStore {
                override suspend fun ensureDirectories()=error("拉取不允许写云端")
                override suspend fun get(path:String):ByteArray?=when {
                    path=="library-index.json" -> indexBytes
                    path.startsWith("library-v3/objects/") -> File(root,"objects/${path.substringAfterLast('/')}").takeIf { it.isFile }?.readBytes()
                    else -> null
                }
                override suspend fun getWithVersion(path:String)=get(path)?.let { CloudRemoteFile(it,sha256(it)) }
                override suspend fun put(path:String,bytes:ByteArray,contentType:String)=error("拉取不允许写云端")
                override suspend fun putIfUnchanged(path:String,bytes:ByteArray,contentType:String,expectedVersion:String?)=error("拉取不允许写云端")
                override suspend fun delete(path:String)=error("不允许删除原件")
            }
            val service=DirectionalCloudSyncService(data,temp,remoteFactory={remote})
            val config=CloudBackupConfig("https://example.test","test","synthetic")
            val preview=service.preview(config,CloudSyncMode.Pull)
            assertEquals(10,preview.plan.entries.count { it.kind==CloudConflictKind.Card && it.change==CloudSyncChange.Add })
            assertTrue(preview.plan.entries.none { it.change==CloudSyncChange.Delete })
            val progress=mutableListOf<CloudSyncProgress>()
            service.execute(config,preview.token) { progress+=it }
            val actual=data.readMetadata().document
            val cards=actual.snapshot.cards.associateBy { it.syncId }
            assertEquals(42,cards.size)
            baseline.snapshot.cards.forEach { assertEquals(it,cards[it.syncId]) }
            val reviews=actual.snapshot.reviews.associateBy { it.cardSyncId }
            baseline.snapshot.reviews.forEach { assertEquals(it,reviews[it.cardSyncId]) }
            assertTrue(service.preview(config,CloudSyncMode.Pull).plan.entries.isEmpty())
            assertTrue(File(temp,"incoming").listFiles().orEmpty().isEmpty())
            assertEquals(1f,progress.last().fraction)
            assertEquals(41,index.document.snapshot.cards.size)
        } finally {
            database.close()
            check(temp.canonicalFile.toPath().startsWith(context.cacheDir.canonicalFile.toPath()))
            check(temp.deleteRecursively())
        }
    }
}
